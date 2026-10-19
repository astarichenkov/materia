#!/usr/bin/env bash
# Generates a synthetic presentation video with 5 visually distinct slides plus
# two near-duplicates (to exercise dHash dedup). No copyrighted media.
#
# Slides use static, strongly patterned backgrounds (SMPTE bars, RGB test,
# grid, bar) so the 9x8 dHash can separate them; text labels are overlaid.
#
# Output: demos/fixtures/synthetic-slides.mp4  (~28 s, 640x360, 25 fps)
set -euo pipefail

FF="${FFMPEG_PATH:-C:/tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe}"
OUT_DIR="${1:-target/slide-fixture}"
OUT="${2:-demos/fixtures/synthetic-slides.mp4}"
FONT="C\\:/Windows/Fonts/arial.ttf"
mkdir -p "$OUT_DIR"

slide() { # name source text extra_filter
  local name="$1" source="$2" text="$3" extra="${4:-}"
  local vf="drawtext=fontfile='${FONT}':text='${text}':fontcolor=white:fontsize=36:borderw=2:bordercolor=black@0.7:x=(w-text_w)/2:y=(h-text_h)/2"
  [ -n "$extra" ] && vf="${vf},${extra}"
  "$FF" -hide_banner -loglevel error -y -f lavfi -i "$source" \
    -vf "$vf" -t 4 -c:v libx264 -pix_fmt yuv420p -r 25 "$OUT_DIR/$name.mp4"
}

# 5 visually distinct slides
slide "s1-title"   "color=c=0x102040:s=640x360"            "TITLE Vector Algebra"
slide "s2-seca"    "smptebars=s=640x360"                   "SECTION A Definitions"
slide "s3-secb"    "yuvtestsrc=s=640x360"                  "SECTION B Products"
slide "s4-diagram" "color=c=0x1f2937:s=640x360"            "DIAGRAM Triangle rule" "drawgrid=w=80:h=80:t=2:c=white@0.5"
slide "s5-summary" "gradients=s=640x360:c0=white:c1=0x3b0764:x0=0:y0=0:x1=640:y1=360" "SUMMARY"
# near-duplicates of s1 and s2 -> must be deduplicated despite non-adjacency
slide "s6-dup1"    "color=c=0x123055:s=640x360"            "TITLE Vector Algebra"
slide "s7-dup2"    "smptebars=s=640x360"                   "SECTION A Definitions"

: > "$OUT_DIR/list.txt"
for n in s1-title s2-seca s3-secb s4-diagram s5-summary s6-dup1 s7-dup2; do
  echo "file '$n.mp4'" >> "$OUT_DIR/list.txt"
done
"$FF" -hide_banner -loglevel error -y -f concat -safe 0 -i "$OUT_DIR/list.txt" -c copy "$OUT"
echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
