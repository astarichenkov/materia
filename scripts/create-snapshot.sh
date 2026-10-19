#!/usr/bin/env bash
# Restores a specific day's milestone into snapshots/<day> using git worktree.
#
# Usage: ./scripts/create-snapshot.sh day27 [--force]
set -euo pipefail

DAY="${1:-}"
FORCE="${2:-}"
case "$DAY" in
  day27|day28|day29|day30) ;;
  *) echo "Usage: $0 {day27|day28|day29|day30} [--force]" >&2; exit 2 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="week6-$DAY"
TARGET="$REPO_ROOT/snapshots/$DAY"

cd "$REPO_ROOT"

if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "Tag $TAG not found. Run: git fetch --tags" >&2
  exit 1
fi

if [ -e "$TARGET" ]; then
  if [ "$FORCE" != "--force" ]; then
    echo "Snapshot path already exists: $TARGET (use --force)" >&2
    exit 1
  fi
  git worktree remove --force "$TARGET" 2>/dev/null || true
  rm -rf "$TARGET"
fi

mkdir -p "$REPO_ROOT/snapshots"
git worktree add --detach "$TARGET" "$TAG"
echo "Snapshot ready: $TARGET"
