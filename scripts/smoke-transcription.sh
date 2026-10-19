#!/usr/bin/env bash
# End-to-end smoke test for the transcription pipeline (Days 27).
# Requires FFmpeg + whisper.cpp + a model. Skips cleanly when tools are missing.
#
# Usage: ./scripts/smoke-transcription.sh <video> [model]
set -euo pipefail

VIDEO="${1:-}"
MODEL="${2:-${WHISPER_MODEL_PATH:-}}"
FFMPEG="${FFMPEG_PATH:-ffmpeg}"
WHISPER="${WHISPER_PATH:-whisper-cli}"

if [ -z "$VIDEO" ] || [ ! -f "$VIDEO" ]; then
  echo "Usage: $0 <video> [model]" >&2; exit 2
fi
if ! command -v "$FFMPEG" >/dev/null 2>&1; then
  echo "SKIPPED: FFmpeg not found ($FFMPEG)"; exit 0
fi
if ! command -v "$WHISPER" >/dev/null 2>&1; then
  echo "SKIPPED: whisper.cpp not found ($WHISPER)"; exit 0
fi
if [ -z "$MODEL" ] || [ ! -f "$MODEL" ]; then
  echo "SKIPPED: Whisper model not set/found (WHISPER_MODEL_PATH)"; exit 0
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[1/2] Extracting audio..."
"$FFMPEG" -hide_banner -y -i "$VIDEO" -vn -ac 1 -ar 16000 -c:a pcm_s16le "$WORK/audio.wav"

echo "[2/2] Transcribing..."
"$WHISPER" -m "$MODEL" -f "$WORK/audio.wav" -oj -of "$WORK/transcript" -l ru

if [ -s "$WORK/transcript.json" ]; then
  echo "PASS: transcript written ($(wc -c < "$WORK/transcript.json") bytes)"
else
  echo "FAIL: no transcript produced" >&2; exit 1
fi
