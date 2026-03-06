#!/usr/bin/env python3
"""
Tests for convert_upload.py

Validates:
    1. gamestate.bin binary parsing (fixed 64-byte big-endian records)
    2. gamestate_events.bin binary parsing (variable-length records)
    3. Parquet file generation with correct schema and values
    4. S3 upload invocation (mocked)
    5. Local binary file cleanup after conversion
    6. Edge cases: empty files, truncated records, missing files

Run with: pytest scripts/test_convert_upload.py -v
"""

import json
import struct
import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import polars as pl
import pytest

# Add scripts directory to path so we can import convert_upload
sys.path.insert(0, str(Path(__file__).parent))
import convert_upload


# ---------------------------------------------------------------------------
# Helpers: write binary data matching Kotlin DataOutputStream (big-endian)
# ---------------------------------------------------------------------------

def write_gamestate_record(
    tick: int = 0,
    timestamp_ms: int = 1000,
    player_x: float = 100.5,
    player_y: float = 64.0,
    player_z: float = -200.25,
    player_yaw: float = 45.0,
    player_pitch: float = -10.0,
    health: float = 20.0,
    hunger: int = 18,
    xp: int = 7,
    biome_id: int = 1,
    light_level: int = 15,
    is_raining: int = 0,
    time_of_day: int = 6000,
    key_mask: int = 0b0000101,  # forward + left
    yaw_delta: float = 1.5,
    pitch_delta: float = -0.3,
) -> bytes:
    """Write a single 64-byte gamestate record in big-endian format,
    matching GameStateWriter.kt writeTick() exactly."""
    return struct.pack(
        ">iqffffffiihbbiiff",
        tick,
        timestamp_ms,
        player_x,
        player_y,
        player_z,
        player_yaw,
        player_pitch,
        health,
        hunger,
        xp,
        biome_id,
        light_level,
        is_raining,
        time_of_day,
        key_mask,
        yaw_delta,
        pitch_delta,
    )


def write_event_record(
    tick: int,
    event_type: int,
    item_name: str,
    count: int,
    prev_count: int,
) -> bytes:
    """Write a single variable-length event record in big-endian format,
    matching GameStateEventWriter.kt exactly."""
    name_bytes = item_name.encode("utf-8")
    name_len = min(len(name_bytes), 255)
    header = struct.pack(">iBB", tick, event_type, name_len)
    tail = struct.pack(">hh", count, prev_count)
    return header + name_bytes[:name_len] + tail


# ---------------------------------------------------------------------------
# Tests: record size verification
# ---------------------------------------------------------------------------

class TestRecordSize:
    def test_gamestate_record_is_64_bytes(self):
        record = write_gamestate_record()
        assert len(record) == 64

    def test_struct_format_size_is_64(self):
        assert struct.calcsize(convert_upload.TICK_RECORD_FORMAT) == 64

    def test_tick_columns_count_matches_format(self):
        """Number of columns must match number of fields in struct format."""
        n_fields = len(struct.unpack(convert_upload.TICK_RECORD_FORMAT, b"\x00" * 64))
        assert len(convert_upload.TICK_COLUMNS) == n_fields


# ---------------------------------------------------------------------------
# Tests: gamestate.bin parsing
# ---------------------------------------------------------------------------

class TestReadGamestateBin:
    def test_single_record(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate.bin"
        bin_file.write_bytes(write_gamestate_record(
            tick=42, timestamp_ms=5000, player_x=1.0, player_y=2.0, player_z=3.0,
            player_yaw=90.0, player_pitch=-45.0, health=20.0,
            hunger=18, xp=5, biome_id=4, light_level=12, is_raining=1,
            time_of_day=12000, key_mask=0b0110011, yaw_delta=2.0, pitch_delta=-1.0,
        ))

        df = convert_upload.read_gamestate_bin(bin_file)
        assert len(df) == 1
        row = df.row(0, named=True)
        assert row["tick"] == 42
        assert row["timestamp_ms"] == 5000
        assert abs(row["player_x"] - 1.0) < 1e-5
        assert abs(row["player_y"] - 2.0) < 1e-5
        assert abs(row["player_z"] - 3.0) < 1e-5
        assert abs(row["player_yaw"] - 90.0) < 1e-5
        assert abs(row["player_pitch"] - (-45.0)) < 1e-5
        assert abs(row["health"] - 20.0) < 1e-5
        assert row["hunger"] == 18
        assert row["xp"] == 5
        assert row["biome_id"] == 4
        assert row["light_level"] == 12
        assert row["is_raining"] == 1
        assert row["time_of_day"] == 12000
        assert row["key_mask"] == 0b0110011
        assert abs(row["yaw_delta"] - 2.0) < 1e-5
        assert abs(row["pitch_delta"] - (-1.0)) < 1e-5

    def test_multiple_records(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate.bin"
        data = b""
        for i in range(100):
            data += write_gamestate_record(tick=i, timestamp_ms=i * 50)
        bin_file.write_bytes(data)

        df = convert_upload.read_gamestate_bin(bin_file)
        assert len(df) == 100
        assert df["tick"].to_list() == list(range(100))

    def test_empty_file(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate.bin"
        bin_file.write_bytes(b"")

        df = convert_upload.read_gamestate_bin(bin_file)
        assert len(df) == 0
        assert set(df.columns) == set(convert_upload.TICK_COLUMNS)

    def test_truncated_file_drops_partial_record(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate.bin"
        full_record = write_gamestate_record(tick=0)
        # Write 1 full record + 30 bytes of a partial record
        bin_file.write_bytes(full_record + b"\x00" * 30)

        df = convert_upload.read_gamestate_bin(bin_file)
        assert len(df) == 1  # Only the complete record

    def test_hunger_is_integer_not_float(self, tmp_path: Path):
        """Regression test: hunger field must be decoded as int, not float."""
        bin_file = tmp_path / "gamestate.bin"
        bin_file.write_bytes(write_gamestate_record(hunger=20))

        df = convert_upload.read_gamestate_bin(bin_file)
        row = df.row(0, named=True)
        # If hunger was decoded as float (the old bug), this would be ~2.8e-44
        assert row["hunger"] == 20
        assert isinstance(row["hunger"], int)


# ---------------------------------------------------------------------------
# Tests: gamestate_events.bin parsing
# ---------------------------------------------------------------------------

class TestReadGamestateEventsBin:
    def test_single_inventory_delta(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate_events.bin"
        bin_file.write_bytes(write_event_record(
            tick=10, event_type=0, item_name="oak_log", count=5, prev_count=0,
        ))

        df = convert_upload.read_gamestate_events_bin(bin_file)
        assert len(df) == 1
        row = df.row(0, named=True)
        assert row["tick"] == 10
        assert row["event_type"] == 0
        assert row["item_name"] == "oak_log"
        assert row["count"] == 5
        assert row["prev_count"] == 0

    def test_multiple_events(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate_events.bin"
        data = b""
        data += write_event_record(tick=1, event_type=0, item_name="oak_log", count=3, prev_count=0)
        data += write_event_record(tick=5, event_type=0, item_name="cobblestone", count=10, prev_count=0)
        data += write_event_record(tick=20, event_type=2, item_name="player", count=0, prev_count=0)
        data += write_event_record(tick=30, event_type=3, item_name="bed", count=0, prev_count=0)
        bin_file.write_bytes(data)

        df = convert_upload.read_gamestate_events_bin(bin_file)
        assert len(df) == 4
        assert df["tick"].to_list() == [1, 5, 20, 30]
        assert df["event_type"].to_list() == [0, 0, 2, 3]
        assert df["item_name"].to_list() == ["oak_log", "cobblestone", "player", "bed"]

    def test_empty_file(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate_events.bin"
        bin_file.write_bytes(b"")

        df = convert_upload.read_gamestate_events_bin(bin_file)
        assert len(df) == 0

    def test_truncated_event_drops_partial(self, tmp_path: Path):
        bin_file = tmp_path / "gamestate_events.bin"
        full_event = write_event_record(tick=1, event_type=0, item_name="oak_log", count=1, prev_count=0)
        # Write 1 full event + truncated header
        bin_file.write_bytes(full_event + b"\x00\x00\x00")

        df = convert_upload.read_gamestate_events_bin(bin_file)
        assert len(df) == 1

    def test_event_types(self, tmp_path: Path):
        """Verify all 4 event types are correctly parsed."""
        bin_file = tmp_path / "gamestate_events.bin"
        data = b""
        data += write_event_record(tick=1, event_type=0, item_name="stone", count=1, prev_count=0)  # INVENTORY_DELTA
        data += write_event_record(tick=2, event_type=1, item_name="wooden_pickaxe", count=1, prev_count=0)  # CRAFTED
        data += write_event_record(tick=3, event_type=2, item_name="player", count=0, prev_count=0)  # PLAYER_DIED
        data += write_event_record(tick=4, event_type=3, item_name="bed", count=0, prev_count=0)  # SLEPT
        bin_file.write_bytes(data)

        df = convert_upload.read_gamestate_events_bin(bin_file)
        assert len(df) == 4
        assert df["event_type"].to_list() == [0, 1, 2, 3]


# ---------------------------------------------------------------------------
# Tests: Parquet output
# ---------------------------------------------------------------------------

class TestParquetConversion:
    def test_gamestate_roundtrip(self, tmp_path: Path):
        """Write binary -> convert to parquet -> read back and verify."""
        bin_file = tmp_path / "gamestate.bin"
        records = []
        for i in range(10):
            records.append(write_gamestate_record(
                tick=i, timestamp_ms=i * 50, player_x=float(i), hunger=20 - i,
            ))
        bin_file.write_bytes(b"".join(records))

        df = convert_upload.read_gamestate_bin(bin_file)
        parquet_file = tmp_path / "tick_stream.parquet"
        df.write_parquet(parquet_file)

        # Read back and verify
        df_read = pl.read_parquet(parquet_file)
        assert len(df_read) == 10
        assert df_read["tick"].to_list() == list(range(10))
        assert df_read["hunger"].to_list() == [20 - i for i in range(10)]

    def test_events_roundtrip(self, tmp_path: Path):
        """Write binary events -> convert to parquet -> read back and verify."""
        bin_file = tmp_path / "gamestate_events.bin"
        data = b""
        data += write_event_record(tick=1, event_type=0, item_name="diamond", count=3, prev_count=1)
        data += write_event_record(tick=5, event_type=0, item_name="iron_ingot", count=0, prev_count=5)
        bin_file.write_bytes(data)

        df = convert_upload.read_gamestate_events_bin(bin_file)
        parquet_file = tmp_path / "events.parquet"
        df.write_parquet(parquet_file)

        df_read = pl.read_parquet(parquet_file)
        assert len(df_read) == 2
        assert df_read["item_name"].to_list() == ["diamond", "iron_ingot"]
        assert df_read["count"].to_list() == [3, 0]
        assert df_read["prev_count"].to_list() == [1, 5]


# ---------------------------------------------------------------------------
# Tests: S3 upload
# ---------------------------------------------------------------------------

class TestS3Upload:
    @patch("convert_upload.boto3")
    def test_upload_calls_s3_for_each_file(self, mock_boto3, tmp_path: Path):
        mock_client = MagicMock()
        mock_boto3.client.return_value = mock_client

        # Create fake files
        f1 = tmp_path / "tick_stream.parquet"
        f1.write_bytes(b"parquet data 1")
        f2 = tmp_path / "events.parquet"
        f2.write_bytes(b"parquet data 2")
        f3 = tmp_path / "manifest.json"
        f3.write_text('{"status": "complete"}')

        files = {
            "tick_stream.parquet": f1,
            "events.parquet": f2,
            "manifest.json": f3,
        }

        convert_upload.upload_to_s3("my-bucket", "us-east-1", "test-uuid", files)

        mock_boto3.client.assert_called_once_with("s3", region_name="us-east-1")
        assert mock_client.upload_file.call_count == 3

        # Verify S3 keys follow the expected pattern
        calls = mock_client.upload_file.call_args_list
        uploaded_keys = [call[0][2] for call in calls]
        assert "raw/session_id=test-uuid/tick_stream.parquet" in uploaded_keys
        assert "raw/session_id=test-uuid/events.parquet" in uploaded_keys
        assert "raw/session_id=test-uuid/manifest.json" in uploaded_keys

    @patch("convert_upload.boto3")
    def test_upload_skips_missing_files(self, mock_boto3, tmp_path: Path):
        mock_client = MagicMock()
        mock_boto3.client.return_value = mock_client

        existing = tmp_path / "tick_stream.parquet"
        existing.write_bytes(b"data")
        missing = tmp_path / "events.parquet"  # Does not exist

        files = {
            "tick_stream.parquet": existing,
            "events.parquet": missing,
        }

        convert_upload.upload_to_s3("bucket", "us-east-1", "uuid", files)
        assert mock_client.upload_file.call_count == 1


# ---------------------------------------------------------------------------
# Tests: full end-to-end pipeline
# ---------------------------------------------------------------------------

class TestEndToEnd:
    @patch("convert_upload.upload_to_s3")
    def test_full_pipeline(self, mock_upload, tmp_path: Path):
        """Simulate a complete session: write binary files, run main, verify outputs."""
        session_dir = tmp_path / "session"
        session_dir.mkdir()

        # Write gamestate.bin with 5 ticks
        gamestate_data = b""
        for i in range(5):
            gamestate_data += write_gamestate_record(tick=i, timestamp_ms=i * 50, hunger=20)
        (session_dir / "gamestate.bin").write_bytes(gamestate_data)

        # Write gamestate_events.bin with 2 events
        events_data = b""
        events_data += write_event_record(tick=1, event_type=0, item_name="stone", count=3, prev_count=0)
        events_data += write_event_record(tick=3, event_type=0, item_name="stone", count=0, prev_count=3)
        (session_dir / "gamestate_events.bin").write_bytes(events_data)

        # Write manifest.json
        manifest = {"session_id": "test-uuid", "status": "complete"}
        (session_dir / "manifest.json").write_text(json.dumps(manifest))

        # Run main
        with patch.object(sys, "argv", [
            "convert_upload.py",
            str(session_dir),
            "test-uuid",
            "my-bucket",
            "us-east-1",
        ]):
            convert_upload.main()

        # Verify parquet files were created
        tick_pq = session_dir / "tick_stream.parquet"
        events_pq = session_dir / "events.parquet"
        assert tick_pq.exists(), "tick_stream.parquet should be created"
        assert events_pq.exists(), "events.parquet should be created"

        # Verify parquet content
        df_ticks = pl.read_parquet(tick_pq)
        assert len(df_ticks) == 5
        assert df_ticks["tick"].to_list() == [0, 1, 2, 3, 4]
        assert all(h == 20 for h in df_ticks["hunger"].to_list())

        df_events = pl.read_parquet(events_pq)
        assert len(df_events) == 2
        assert df_events["item_name"].to_list() == ["stone", "stone"]
        assert df_events["count"].to_list() == [3, 0]

        # Verify upload_to_s3 was called with correct args
        mock_upload.assert_called_once()
        call_args = mock_upload.call_args
        assert call_args[0][0] == "my-bucket"
        assert call_args[0][1] == "us-east-1"
        assert call_args[0][2] == "test-uuid"
        uploaded_files = call_args[0][3]
        assert "tick_stream.parquet" in uploaded_files
        assert "events.parquet" in uploaded_files
        assert "manifest.json" in uploaded_files

        # Verify binary files were deleted
        assert not (session_dir / "gamestate.bin").exists(), "gamestate.bin should be deleted"
        assert not (session_dir / "gamestate_events.bin").exists(), "gamestate_events.bin should be deleted"

        # Verify manifest.json still exists (not deleted)
        assert (session_dir / "manifest.json").exists(), "manifest.json should NOT be deleted"

    @patch("convert_upload.upload_to_s3")
    def test_missing_gamestate_bin(self, mock_upload, tmp_path: Path):
        """Pipeline should handle missing gamestate.bin gracefully."""
        session_dir = tmp_path / "session"
        session_dir.mkdir()

        # Only events file exists
        events_data = write_event_record(tick=1, event_type=0, item_name="dirt", count=1, prev_count=0)
        (session_dir / "gamestate_events.bin").write_bytes(events_data)
        (session_dir / "manifest.json").write_text('{"status": "complete"}')

        with patch.object(sys, "argv", [
            "convert_upload.py",
            str(session_dir),
            "test-uuid",
            "bucket",
            "us-east-1",
        ]):
            convert_upload.main()

        # events.parquet should still be created
        assert (session_dir / "events.parquet").exists()
        # tick_stream.parquet should NOT exist
        assert not (session_dir / "tick_stream.parquet").exists()
        # Upload should still be called (for events + manifest)
        mock_upload.assert_called_once()

    @patch("convert_upload.upload_to_s3")
    def test_missing_events_bin(self, mock_upload, tmp_path: Path):
        """Pipeline should handle missing gamestate_events.bin gracefully."""
        session_dir = tmp_path / "session"
        session_dir.mkdir()

        # Only gamestate file exists
        (session_dir / "gamestate.bin").write_bytes(write_gamestate_record(tick=0))
        (session_dir / "manifest.json").write_text('{"status": "complete"}')

        with patch.object(sys, "argv", [
            "convert_upload.py",
            str(session_dir),
            "test-uuid",
            "bucket",
            "us-east-1",
        ]):
            convert_upload.main()

        assert (session_dir / "tick_stream.parquet").exists()
        assert not (session_dir / "events.parquet").exists()
        mock_upload.assert_called_once()

    @patch("convert_upload.upload_to_s3")
    def test_empty_session_no_files(self, mock_upload, tmp_path: Path):
        """Pipeline should handle a session dir with no binary files."""
        session_dir = tmp_path / "session"
        session_dir.mkdir()

        with patch.object(sys, "argv", [
            "convert_upload.py",
            str(session_dir),
            "test-uuid",
            "bucket",
            "us-east-1",
        ]):
            convert_upload.main()

        # No parquet files should be created
        assert not (session_dir / "tick_stream.parquet").exists()
        assert not (session_dir / "events.parquet").exists()


# ---------------------------------------------------------------------------
# Tests: format string matches Kotlin writer
# ---------------------------------------------------------------------------

class TestFormatKotlinAlignment:
    """Verify the Python struct format produces the same bytes as Java DataOutputStream."""

    def test_format_field_count(self):
        """17 fields in format must match 17 column names."""
        unpacked = struct.unpack(convert_upload.TICK_RECORD_FORMAT, b"\x00" * 64)
        assert len(unpacked) == 17
        assert len(convert_upload.TICK_COLUMNS) == 17

    def test_known_values_roundtrip(self):
        """Pack known values, unpack, and verify they come back correctly."""
        values = (
            42,        # tick: i
            123456789, # timestamp_ms: q
            1.5,       # player_x: f
            64.0,      # player_y: f
            -200.0,    # player_z: f
            90.0,      # player_yaw: f
            -45.0,     # player_pitch: f
            20.0,      # health: f
            18,        # hunger: i  <-- this was the bug (was 'f' before)
            7,         # xp: i
            4,         # biome_id: h
            12,        # light_level: b
            1,         # is_raining: b
            6000,      # time_of_day: i
            0b0000101, # key_mask: i
            1.5,       # yaw_delta: f
            -0.3,      # pitch_delta: f
        )
        packed = struct.pack(convert_upload.TICK_RECORD_FORMAT, *values)
        assert len(packed) == 64

        unpacked = struct.unpack(convert_upload.TICK_RECORD_FORMAT, packed)
        assert unpacked[0] == 42        # tick
        assert unpacked[1] == 123456789 # timestamp_ms
        assert abs(unpacked[2] - 1.5) < 1e-5   # player_x
        assert unpacked[8] == 18        # hunger (integer, not float!)
        assert unpacked[9] == 7         # xp
        assert unpacked[10] == 4        # biome_id
        assert unpacked[11] == 12       # light_level
        assert unpacked[12] == 1        # is_raining

    def test_hunger_xp_are_integers_in_struct(self):
        """Explicitly verify hunger and xp fields use 'i' (int) format."""
        fmt = convert_upload.TICK_RECORD_FORMAT
        # The format is: >iqffffffiihbbiiff
        # Positions after '>': i q f f f f f f i i h b b i i f f
        # hunger is the 9th field (index 8), xp is the 10th (index 9)
        # Both should be decoded as int
        data = write_gamestate_record(hunger=20, xp=30)
        unpacked = struct.unpack(convert_upload.TICK_RECORD_FORMAT, data)
        assert isinstance(unpacked[8], int), f"hunger should be int, got {type(unpacked[8])}"
        assert unpacked[8] == 20
        assert isinstance(unpacked[9], int), f"xp should be int, got {type(unpacked[9])}"
        assert unpacked[9] == 30
