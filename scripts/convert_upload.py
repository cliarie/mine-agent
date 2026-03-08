#!/usr/bin/env python3
"""
convert_upload.py - Convert binary ML session files to Parquet and upload to S3.

Invoked by the MCAP mod on session end via ProcessBuilder (fire-and-forget).

Usage:
    python3 scripts/convert_upload.py <session_dir> <session_id> <bucket> <region>
    python3 scripts/convert_upload.py --convert-only <session_dir> <session_id>

Steps:
    1. Read gamestate.bin (fixed 64-byte big-endian records) -> polars DataFrame
    2. Read gamestate_events.bin (variable-length records) -> polars DataFrame
    3. Write tick_stream.parquet and events.parquet
    4. Upload tick_stream.parquet, events.parquet, manifest.json to S3 (skipped with --convert-only)
    5. Delete local binary files (gamestate.bin, gamestate_events.bin) (skipped with --convert-only)

Requirements: python3, polars, boto3 (boto3 only needed for S3 upload mode)
"""

import struct
import sys
from pathlib import Path

try:
    import polars as pl
except ImportError:
    print("[convert_upload] ERROR: polars not installed. Run: pip install polars", file=sys.stderr)
    sys.exit(1)


# gamestate.bin record layout (big-endian, 64 bytes per record)
# See GameStateWriter.kt for authoritative field order
TICK_RECORD_FORMAT = ">iqffffffiihbbiiff"
TICK_RECORD_SIZE = struct.calcsize(TICK_RECORD_FORMAT)
assert TICK_RECORD_SIZE == 64, f"Expected 64 bytes, got {TICK_RECORD_SIZE}"

TICK_COLUMNS = [
    "tick",           # i  Int32
    "timestamp_ms",   # q  Int64
    "player_x",       # f  Float32
    "player_y",       # f  Float32
    "player_z",       # f  Float32
    "player_yaw",     # f  Float32
    "player_pitch",   # f  Float32
    "health",         # f  Float32
    "hunger",         # i  Int32
    "xp",             # i  Int32
    "biome_id",       # h  Int16
    "light_level",    # b  Int8
    "is_raining",     # b  Int8
    "time_of_day",    # i  Int32
    "key_mask",       # i  Int32
    "yaw_delta",      # f  Float32
    "pitch_delta",    # f  Float32
]

# gamestate_events.bin record layout (variable-length)
# Header: tick(4) + event_type(1) + item_name_len(1) + item_name(variable) + count(2) + prev_count(2)
EVENT_HEADER_FORMAT = ">iBB"
EVENT_HEADER_SIZE = struct.calcsize(EVENT_HEADER_FORMAT)  # 6 bytes
EVENT_TAIL_FORMAT = ">hh"
EVENT_TAIL_SIZE = struct.calcsize(EVENT_TAIL_FORMAT)  # 4 bytes

# Decode integer event_type to string labels expected by mine-train
EVENT_TYPES = {0: "INVENTORY_DELTA", 1: "CRAFTED", 2: "PLAYER_DIED", 3: "SLEPT"}


def read_gamestate_bin(path: Path) -> pl.DataFrame:
    """Read gamestate.bin into a polars DataFrame."""
    data = path.read_bytes()
    n_records = len(data) // TICK_RECORD_SIZE
    if len(data) % TICK_RECORD_SIZE != 0:
        print(
            f"[convert_upload] WARNING: gamestate.bin size {len(data)} not divisible by {TICK_RECORD_SIZE}, "
            f"truncating to {n_records} records",
            file=sys.stderr,
        )

    rows = []
    for i in range(n_records):
        offset = i * TICK_RECORD_SIZE
        record = struct.unpack_from(TICK_RECORD_FORMAT, data, offset)
        rows.append(record)

    if not rows:
        # Return empty DataFrame with correct schema
        return pl.DataFrame(
            {col: pl.Series([], dtype=pl.Int32) for col in TICK_COLUMNS}
        )

    return pl.DataFrame(rows, schema=TICK_COLUMNS, orient="row")


def read_gamestate_events_bin(path: Path) -> pl.DataFrame:
    """Read gamestate_events.bin into a polars DataFrame."""
    data = path.read_bytes()
    rows = []
    offset = 0

    while offset + EVENT_HEADER_SIZE <= len(data):
        tick, event_type, name_len = struct.unpack_from(EVENT_HEADER_FORMAT, data, offset)
        offset += EVENT_HEADER_SIZE

        if offset + name_len + EVENT_TAIL_SIZE > len(data):
            print(
                f"[convert_upload] WARNING: truncated event record at offset {offset - EVENT_HEADER_SIZE}",
                file=sys.stderr,
            )
            break

        item_name = data[offset : offset + name_len].decode("utf-8", errors="replace")
        offset += name_len

        count, prev_count = struct.unpack_from(EVENT_TAIL_FORMAT, data, offset)
        offset += EVENT_TAIL_SIZE

        rows.append((tick, event_type, item_name, count, prev_count))

    if not rows:
        return pl.DataFrame(
            schema={
                "tick": pl.Int32,
                "event_type": pl.Int8,
                "item_name": pl.Utf8,
                "count": pl.Int16,
                "prev_count": pl.Int16,
            }
        )

    return pl.DataFrame(
        rows,
        schema=["tick", "event_type", "item_name", "count", "prev_count"],
        orient="row",
    )


def upload_to_s3(
    bucket: str, region: str, session_id: str, files: dict[str, Path]
) -> None:
    """Upload files to S3 under raw/session_id={session_id}/."""
    s3 = boto3.client("s3", region_name=region)
    for s3_name, local_path in files.items():
        if not local_path.exists():
            continue
        key = f"raw/session_id={session_id}/{s3_name}"
        print(f"[convert_upload] Uploading {s3_name} -> s3://{bucket}/{key}")
        s3.upload_file(str(local_path), bucket, key)
    print(f"[convert_upload] S3 upload complete for session {session_id}")


def main() -> None:
    convert_only = "--convert-only" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--convert-only"]

    if convert_only:
        if len(args) < 2:
            print(
                f"Usage: {sys.argv[0]} --convert-only <session_dir> <session_id>",
                file=sys.stderr,
            )
            sys.exit(1)
        session_dir = Path(args[0])
        session_id = args[1]
        bucket = None
        region = None
    else:
        if len(args) < 4:
            print(
                f"Usage: {sys.argv[0]} <session_dir> <session_id> <bucket> <region>",
                file=sys.stderr,
            )
            sys.exit(1)
        session_dir = Path(args[0])
        session_id = args[1]
        bucket = args[2]
        region = args[3]

    gamestate_bin = session_dir / "gamestate.bin"
    events_bin = session_dir / "gamestate_events.bin"
    manifest_json = session_dir / "manifest.json"
    tick_stream_parquet = session_dir / "tick_stream.parquet"
    events_parquet = session_dir / "events.parquet"

    # 1. Convert gamestate.bin -> tick_stream.parquet
    if gamestate_bin.exists():
        print(f"[convert_upload] Converting gamestate.bin ({gamestate_bin.stat().st_size} bytes)")
        df = read_gamestate_bin(gamestate_bin)
        df = df.with_columns(pl.lit(session_id).alias("session_id"))
        df.write_parquet(tick_stream_parquet)
        print(f"[convert_upload] Wrote tick_stream.parquet ({len(df)} rows)")
    else:
        print("[convert_upload] WARNING: gamestate.bin not found, skipping", file=sys.stderr)

    # 2. Convert gamestate_events.bin -> events.parquet
    if events_bin.exists():
        print(f"[convert_upload] Converting gamestate_events.bin ({events_bin.stat().st_size} bytes)")
        df_events = read_gamestate_events_bin(events_bin)
        df_events = df_events.with_columns(
            pl.col("event_type").replace_strict(EVENT_TYPES).alias("event_type"),
            pl.lit(session_id).alias("session_id"),
        )
        df_events.write_parquet(events_parquet)
        print(f"[convert_upload] Wrote events.parquet ({len(df_events)} rows)")
    else:
        print("[convert_upload] WARNING: gamestate_events.bin not found, skipping", file=sys.stderr)

    if convert_only:
        print(f"[convert_upload] Conversion complete for session {session_id}")
        return

    # 3. Upload parquet files + manifest.json to S3
    try:
        import boto3
    except ImportError:
        print("[convert_upload] ERROR: boto3 not installed. Run: pip install boto3", file=sys.stderr)
        sys.exit(1)

    files_to_upload: dict[str, Path] = {}
    if tick_stream_parquet.exists():
        files_to_upload["tick_stream.parquet"] = tick_stream_parquet
    if events_parquet.exists():
        files_to_upload["events.parquet"] = events_parquet
    if manifest_json.exists():
        files_to_upload["manifest.json"] = manifest_json

    if files_to_upload:
        upload_to_s3(bucket, region, session_id, files_to_upload)
    else:
        print("[convert_upload] No files to upload", file=sys.stderr)

    # 4. Delete local binary files (keep parquet + manifest for debugging)
    for bin_file in [gamestate_bin, events_bin]:
        if bin_file.exists():
            bin_file.unlink()
            print(f"[convert_upload] Deleted {bin_file.name}")

    print(f"[convert_upload] Done for session {session_id}")


if __name__ == "__main__":
    main()
