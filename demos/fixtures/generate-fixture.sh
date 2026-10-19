#!/usr/bin/env bash
# Generates a short synthetic lecture video fixture with FFmpeg.
# Usage: ./generate-fixture.sh [output.mp4] [seconds] [ffmpeg-binary]
set -euo pipefail

OUTPUT="${1:-demos/fixtures/sample-lecture.mp4}"
SECONDS="${2:-30}"
FFMPEG="${3:-ffmpeg}"

mkdir -p "$(dirname "$OUTPUT")"
"$FFMPEG" -y \
  -f lavfi -i "testsrc2=size=640x360:rate=10" \
  -f lavfi -i "sine=frequency=440" \
  -t "$SECONDS" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$OUTPUT"
echo "Fixture written to $OUTPUT"
