use chrono::Utc;
use crc32fast::Hasher;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use lz4_flex::{compress_prepend_size, decompress_size_prepended};
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
static REPLAY_SESSIONS: Lazy<Mutex<HashMap<i64, ReplaySession>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static NEXT_HANDLE: Lazy<Mutex<i64>> = Lazy::new(|| Mutex::new(1));

#[derive(Debug)]
struct ReplaySession {
    session_dir: PathBuf,
    chunks_dir: PathBuf,
    db: Connection,
    record_size: usize,
    max_tick: i32,
}

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
            record_size: 48,   // enhanced format: flags, mouse, position, health, velocity, cursor
            pending: Vec::with_capacity(400 * 48),
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
    session.pending.extend_from_slice(&buf[0..len]);

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
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeAppendPackets(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) {
    let len = len as usize;
    if len == 0 {
        return;
    }
    let buf = env.convert_byte_array(&data).unwrap();

    let mut sessions = SESSIONS.lock().unwrap();
    let session = match sessions.get_mut(&(handle as i64)) {
        Some(s) => s,
        None => return,
    };

    // Write packets to a separate file (packets.bin)
    let packets_path = session.session_dir.join("packets.bin");
    let mut file = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&packets_path)
        .unwrap();
    
    file.write_all(&buf[0..len]).unwrap();

    // Write version marker for new packet format (v2: includes timestamp_ms)
    let marker_path = session.session_dir.join("packets_v2.marker");
    if !marker_path.exists() {
        let _ = fs::write(&marker_path, b"v2");
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

// ============== REPLAY FUNCTIONS ==============

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeOpenReplay(
    mut env: JNIEnv,
    _class: JClass,
    session_path: JString,
) -> jlong {
    let session_path: String = env.get_string(&session_path).unwrap().into();
    let session_dir = PathBuf::from(&session_path);
    let chunks_dir = session_dir.join("chunks");
    let db_path = session_dir.join("capture.sqlite");

    if !db_path.exists() {
        return -1;
    }

    let db = match Connection::open(&db_path) {
        Ok(c) => c,
        Err(_) => return -1,
    };

    // Get max tick from database
    let max_tick: i32 = db
        .query_row("SELECT COALESCE(MAX(endTick), -1) FROM chunks", [], |row| row.get(0))
        .unwrap_or(-1);

    let handle = alloc_handle();
    let replay = ReplaySession {
        session_dir,
        chunks_dir,
        db,
        record_size: 48,
        max_tick,
    };

    REPLAY_SESSIONS.lock().unwrap().insert(handle, replay);
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeGetReplayMaxTick(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    let sessions = REPLAY_SESSIONS.lock().unwrap();
    match sessions.get(&(handle as i64)) {
        Some(s) => s.max_tick as jint,
        None => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeReadTick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    tick: jint,
) -> JByteArray<'local> {
    let sessions = REPLAY_SESSIONS.lock().unwrap();
    let session = match sessions.get(&(handle as i64)) {
        Some(s) => s,
        None => return env.new_byte_array(0).unwrap(),
    };

    // Find chunk containing this tick
    let chunk_info: Result<(String, i32), _> = session.db.query_row(
        "SELECT path, startTick FROM chunks WHERE startTick <= ?1 AND endTick >= ?1 LIMIT 1",
        params![tick as i64],
        |row| Ok((row.get(0)?, row.get(1)?)),
    );

    let (chunk_path, start_tick) = match chunk_info {
        Ok(info) => info,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    // Read and decompress chunk
    let full_path = session.chunks_dir.join(&chunk_path);
    let data = match fs::read(&full_path) {
        Ok(d) => d,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    if data.len() < 25 {
        return env.new_byte_array(0).unwrap();
    }

    // Skip header (25 bytes), decompress payload
    let payload = &data[25..];
    let decompressed = match decompress_size_prepended(payload) {
        Ok(d) => d,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    // Extract single tick record
    let tick_offset = (tick - start_tick) as usize;
    let byte_offset = tick_offset * session.record_size;
    
    if byte_offset + session.record_size > decompressed.len() {
        return env.new_byte_array(0).unwrap();
    }

    let record = &decompressed[byte_offset..byte_offset + session.record_size];
    
    let result = env.new_byte_array(session.record_size as i32).unwrap();
    let _ = env.set_byte_array_region(&result, 0, bytemuck::cast_slice(record));
    result
}

/// Read all packets for a specific tick from packets.bin
/// Supports both old format (u32 tick, u16 packetId, u16 dataLen, data[])
/// and new format (u32 tick, u32 timestamp_ms, u16 packetId, u32 dataLen, data[])
/// Returns array of packets: u16 packetId, u32 dataLen, data[]
#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeReadPacketsForTick<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    tick: jint,
) -> JByteArray<'local> {
    let sessions = REPLAY_SESSIONS.lock().unwrap();
    let session = match sessions.get(&(handle as i64)) {
        Some(s) => s,
        None => return env.new_byte_array(0).unwrap(),
    };

    let packets_path = session.session_dir.join("packets.bin");
    if !packets_path.exists() {
        return env.new_byte_array(0).unwrap();
    }

    let data = match fs::read(&packets_path) {
        Ok(d) => d,
        Err(_) => return env.new_byte_array(0).unwrap(),
    };

    // Detect format: new format has u32 tick + u32 timestamp_ms + u16 packetId + u32 dataLen (=14 byte header)
    // old format has u32 tick + u16 packetId + u16 dataLen (=8 byte header)
    // We detect by checking if a version marker file exists
    let is_new_format = session.session_dir.join("packets_v2.marker").exists();
    let header_size: usize = if is_new_format { 14 } else { 8 };

    let mut result: Vec<u8> = Vec::new();
    let mut offset = 0;

    while offset + header_size <= data.len() {
        let pkt_tick = u32::from_le_bytes([data[offset], data[offset+1], data[offset+2], data[offset+3]]);

        let (pkt_id, data_len) = if is_new_format {
            // Skip timestamp_ms (offset+4..offset+8)
            let id = u16::from_le_bytes([data[offset+8], data[offset+9]]);
            let len = u32::from_le_bytes([data[offset+10], data[offset+11], data[offset+12], data[offset+13]]) as usize;
            (id, len)
        } else {
            let id = u16::from_le_bytes([data[offset+4], data[offset+5]]);
            let len = u16::from_le_bytes([data[offset+6], data[offset+7]]) as usize;
            (id, len)
        };

        offset += header_size;

        if offset + data_len > data.len() {
            break;
        }

        if pkt_tick == tick as u32 {
            result.extend_from_slice(&pkt_id.to_le_bytes());
            result.extend_from_slice(&(data_len as u32).to_le_bytes());
            result.extend_from_slice(&data[offset..offset + data_len]);
        }

        offset += data_len;
    }

    if result.is_empty() {
        return env.new_byte_array(0).unwrap();
    }

    let jarray = env.new_byte_array(result.len() as i32).unwrap();
    let _ = env.set_byte_array_region(&jarray, 0, bytemuck::cast_slice(&result));
    jarray
}

#[no_mangle]
pub extern "system" fn Java_dev_replaycraft_mcap_native_NativeBridge_nativeCloseReplay(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    REPLAY_SESSIONS.lock().unwrap().remove(&(handle as i64));
}
