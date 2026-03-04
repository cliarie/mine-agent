#!/usr/bin/env bash
#
# record_and_export.sh - Automation launcher for MCAP replay capture
#
# This script:
#   1. Launches Minecraft with the MCAP mod
#   2. Waits for a recording session to complete
#   3. Exports the session to JSON Lines format
#   4. Optionally uploads to cheap storage (S3, GCS, local)
#
# Usage:
#   ./scripts/record_and_export.sh [--output-dir DIR] [--upload s3://bucket/prefix]
#
# Requirements:
#   - Minecraft instance with Fabric + MCAP mod installed
#   - Rust binaries built (cargo build --release in native/)
#   - ffmpeg installed (for video export)
#
# The mod auto-captures gameplay while the player is in a world.
# Sessions are stored in <minecraft_dir>/mcap_replay/sessions/<timestamp>/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_BIN="$REPO_ROOT/native/target/release"

# Defaults
OUTPUT_DIR="${OUTPUT_DIR:-./mcap_export}"
UPLOAD_TARGET=""
MC_DIR="${MC_DIR:-$HOME/.minecraft}"
SESSIONS_DIR="$MC_DIR/mcap_replay/sessions"
EXPORT_JSON="${NATIVE_BIN}/export_json"
SIMULATOR="${NATIVE_BIN}/simulator"

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --output-dir DIR       Output directory for exports (default: ./mcap_export)"
    echo "  --upload TARGET        Upload target (s3://bucket/prefix or gs://bucket/prefix)"
    echo "  --mc-dir DIR           Minecraft directory (default: ~/.minecraft)"
    echo "  --session SESSION      Export specific session (directory name)"
    echo "  --latest               Export only the latest session"
    echo "  --video                Also export video (requires ffmpeg)"
    echo "  --simulate             Run simulator on exported data"
    echo "  --help                 Show this help"
    echo ""
    echo "Environment variables:"
    echo "  MC_DIR                 Minecraft directory"
    echo "  OUTPUT_DIR             Output directory"
}

# Parse args
SESSION=""
LATEST=false
VIDEO=false
SIMULATE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --upload) UPLOAD_TARGET="$2"; shift 2 ;;
        --mc-dir) MC_DIR="$2"; SESSIONS_DIR="$MC_DIR/mcap_replay/sessions"; shift 2 ;;
        --session) SESSION="$2"; shift 2 ;;
        --latest) LATEST=true; shift ;;
        --video) VIDEO=true; shift ;;
        --simulate) SIMULATE=true; shift ;;
        --help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

# Check binaries exist
if [[ ! -x "$EXPORT_JSON" ]]; then
    echo "Building native tools..."
    (cd "$REPO_ROOT/native" && cargo build --release --bin export_json --bin simulator --bin inspect_cap)
fi

mkdir -p "$OUTPUT_DIR"

# Find sessions to export
if [[ -n "$SESSION" ]]; then
    SESSIONS=("$SESSIONS_DIR/$SESSION")
elif $LATEST; then
    SESSIONS=("$(ls -dt "$SESSIONS_DIR"/*/ 2>/dev/null | head -1)")
else
    SESSIONS=("$SESSIONS_DIR"/*/)
fi

if [[ ${#SESSIONS[@]} -eq 0 ]] || [[ ! -d "${SESSIONS[0]}" ]]; then
    echo "No sessions found in $SESSIONS_DIR"
    echo "Play Minecraft with the MCAP mod to create recordings."
    exit 1
fi

echo "Found ${#SESSIONS[@]} session(s) to export"
echo ""

for session_dir in "${SESSIONS[@]}"; do
    session_name="$(basename "$session_dir")"
    echo "=== Exporting session: $session_name ==="

    # Check session has data
    chunk_count=$(find "$session_dir/chunks" -name "*.cap" 2>/dev/null | wc -l)
    if [[ "$chunk_count" -eq 0 ]]; then
        echo "  Skipping: no capture data"
        continue
    fi
    echo "  Chunks: $chunk_count"

    # Export to JSON Lines
    output_jsonl="$OUTPUT_DIR/${session_name}.jsonl"
    echo "  Exporting to $output_jsonl..."
    "$EXPORT_JSON" "$session_dir" "$output_jsonl"
    echo "  Done: $(wc -l < "$output_jsonl") ticks exported"

    # Run simulator if requested
    if $SIMULATE; then
        sim_output="$OUTPUT_DIR/${session_name}_sim.jsonl"
        echo "  Running simulator..."
        "$SIMULATOR" "$session_dir" --verify --output "$sim_output"
    fi

    # Upload if target specified
    if [[ -n "$UPLOAD_TARGET" ]]; then
        echo "  Uploading to $UPLOAD_TARGET..."
        if [[ "$UPLOAD_TARGET" == s3://* ]]; then
            aws s3 cp "$output_jsonl" "$UPLOAD_TARGET/${session_name}.jsonl"
            if $SIMULATE; then
                aws s3 cp "$sim_output" "$UPLOAD_TARGET/${session_name}_sim.jsonl"
            fi
        elif [[ "$UPLOAD_TARGET" == gs://* ]]; then
            gsutil cp "$output_jsonl" "$UPLOAD_TARGET/${session_name}.jsonl"
            if $SIMULATE; then
                gsutil cp "$sim_output" "$UPLOAD_TARGET/${session_name}_sim.jsonl"
            fi
        else
            # Local copy
            cp "$output_jsonl" "$UPLOAD_TARGET/"
            if $SIMULATE; then
                cp "$sim_output" "$UPLOAD_TARGET/"
            fi
        fi
        echo "  Upload complete."
    fi

    echo ""
done

echo "Export complete. Output in: $OUTPUT_DIR"
