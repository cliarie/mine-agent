use chrono::Utc;
use crc32fast::Hasher;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use lz4_flex::compress_prepend_size;
use rusqlite::{params, Connection};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use once_cell::sync::Lazy;

#[derive(Debug)]
struct Session {
    session_dir: PathBuf,
    chunks_dir: PathBuf,
    db: Connection,

    // chunk assembly
    chunk_ticks: usize,
    record_size: usize,
    pending: Vec<u8>,
    pending_start_tick: i32,
    next_chunk_index: u32,
}

static SESSIONS: Lazy<Mutex<HashMap<i64, Session>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_HANDLE: Lazy<Mutex<i64>> = Lazy::new(|| Mutex::new(1));

fn alloc_handle() -> i64 {
    let mut h = NEXT_HANDLE.lock().unwrap();
    let out = *h;
    *h += 1;
    out
}

fn ensure_dir(p: &Path) {
    fs::create_dir_all(p).unwrap();
}

fn write_file_atomic(path: &Path, bytes: &[u8]) {
    let tmp = path.with_extension("tmp");
    {
        let mut f = fs::File::create(&tmp).unwrap();
        f.write_all(bytes).unwrap();
        f.sync_all().unwrap();
    }
    fs::rename(tmp, path).unwrap();
}

fn init_db(db_path: &Path) -> Connection {
    let db = Connection::open(db_path).unwrap();
    db.execute_batch(
        "\
        PRAGMA journal_mode=WAL;\
        PRAGMA synchronous=NORMAL;\
        CREATE TABLE IF NOT EXISTS chunks(\
            startTick INTEGER NOT NULL,\
            endTick INTEGER NOT NULL,\
            path TEXT NOT NULL,\
            fileOffset INTEGER NOT NULL,\
            byteLen INTEGER NOT NULL,\
            crc INTEGER NOT NULL\
        );\
        CREATE INDEX IF NOT EXISTS idx_chunks_start ON chunks(startTick);\
        CREATE INDEX IF NOT EXISTS idx_chunks_end ON chunks(endTick);\
        ",
    )
    .unwrap();
    db
}

fn chunk_file_name(idx: u32) -> String {
    format!("{:06}.cap", idx)
}

fn write_chunk(session: &mut Session, start_tick: i32, tick_count: usize, uncompressed: &[u8]) {
    if tick_count == 0 {
        return;
    }

    // Compress (payload only)
    let compressed = compress_prepend_size(uncompressed);
    let mut hasher = Hasher::new();
    hasher.update(&compressed);
    let crc = hasher.finalize();

    // Header
    // magic(4) schema(u16) startTick(u32) tickCount(u16) codec(u8) uncompressedLen(u32) compressedLen(u32) crc32(u32)
    let schema_version: u16 = 1;
    let codec: u8 = 1; // 1 = lz4
    let uncompressed_len: u32 = uncompressed.len() as u32;
    let compressed_len: u32 = compressed.len() as u32;

    let mut header = Vec::with_capacity(4 + 2 + 4 + 2 + 1 + 4 + 4 + 4);
    header.extend_from_slice(b"MCAP");
    header.extend_from_slice(&schema_version.to_le_bytes());
    header.extend_from_slice(&(start_tick as u32).to_le_bytes());
    header.extend_from_slice(&(tick_count as u16).to_le_bytes());
    header.push(codec);
    header.extend_from_slice(&uncompressed_len.to_le_bytes());
    header.extend_from_slice(&compressed_len.to_le_bytes());
    header.extend_from_slice(&crc.to_le_bytes());

    let file_name = chunk_file_name(session.next_chunk_index);
    session.next_chunk_index += 1;

    let chunk_path = session.chunks_dir.join(&file_name);
    let mut file_bytes = Vec::with_capacity(header.len() + compressed.len());
    file_bytes.extend_from_slice(&header);
    file_bytes.extend_from_slice(&compressed);
    write_file_atomic(&chunk_path, &file_bytes);

    let end_tick = start_tick + tick_count as i32 - 1;
    session
        .db
        .execute(
            "INSERT INTO chunks(startTick,endTick,path,fileOffset,byteLen,crc) VALUES (?1,?2,?3,?4,?5,?6)",
            params![
                start_tick as i64,
                end_tick as i64,
                chunk_path
                    .file_name()
                    .unwrap()
                    .to_string_lossy()
                    .to_string(),
                0i64,
                file_bytes.len() as i64,
                crc as i64
            ],
        )
        .unwrap();
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeInitSession(
    mut env: JNIEnv,
    _class: JClass,
    manifest_json: JByteArray,
    base_dir: JString,
) -> jlong {
    let base_dir: String = env.get_string(&base_dir).unwrap().into();
    let manifest = env.convert_byte_array(&manifest_json).unwrap();

    let now = Utc::now();
    let session_id = format!("{}", now.format("%Y%m%dT%H%M%S%.3fZ"));

    let session_dir = Path::new(&base_dir)
        .join("mcap_replay")
        .join("sessions")
        .join(session_id);
    let chunks_dir = session_dir.join("chunks");
    ensure_dir(&chunks_dir);

    // Persist manifest.json
    write_file_atomic(&session_dir.join("manifest.json"), &manifest);

    // Create SQLite index
    let db = init_db(&session_dir.join("capture.sqlite"));

    let handle = alloc_handle();
    let session = Session {
        session_dir,
        chunks_dir,
        db,
        chunk_ticks: 400,  // 20 seconds at 20Hz
        record_size: 12,   // must match JVM packed record
        pending: Vec::with_capacity(400 * 12),
        pending_start_tick: -1,
        next_chunk_index: 0,
    };

    SESSIONS.lock().unwrap().insert(handle, session);
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeAppendTicks(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    start_tick: jint,
    packed: JByteArray,
    len: jint,
) {
    let len = len as usize;
    if len == 0 {
        return;
    }
    let buf = env.convert_byte_array(&packed).unwrap();

    let mut sessions = SESSIONS.lock().unwrap();
    let session = match sessions.get_mut(&(handle as i64)) {
        Some(s) => s,
        None => return,
    };

    // Append into pending buffer and flush to chunks
    if session.pending_start_tick < 0 {
        session.pending_start_tick = start_tick as i32;
    }
    session.pending.extend_from_slice(&buf);

    // Flush whole chunks
    let bytes_per_chunk = session.chunk_ticks * session.record_size;
    while session.pending.len() >= bytes_per_chunk {
        let chunk_bytes = session.pending.drain(0..bytes_per_chunk).collect::<Vec<u8>>();
        let start = session.pending_start_tick;
        write_chunk(session, start, session.chunk_ticks, &chunk_bytes);
        session.pending_start_tick += session.chunk_ticks as i32;
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeCloseSession(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let mut sessions = SESSIONS.lock().unwrap();
    if let Some(mut session) = sessions.remove(&(handle as i64)) {
        // Flush remaining partial chunk
        if session.pending_start_tick >= 0 && !session.pending.is_empty() {
            let tick_count = session.pending.len() / session.record_size;
            let start = session.pending_start_tick;
            let pending_clone = session.pending.clone();
            write_chunk(&mut session, start, tick_count, &pending_clone);
            session.pending.clear();
        }
        let _ = session.db.execute("PRAGMA wal_checkpoint(TRUNCATE)", []);
    }
}
