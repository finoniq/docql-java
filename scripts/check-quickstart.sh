#!/usr/bin/env bash
# scripts/check-quickstart.sh — keep examples/Quickstart.java and the README
# quickstart telling the same story, and validate the install blocks that a
# downstream consumer vendors from the release tag.
#
# Extracts the single fenced Java block between the docql:quickstart markers
# of README.md and compares it byte for byte with examples/Quickstart.java,
# then checks that the docql:install-maven and docql:install-gradle blocks
# each appear once and name the version the SDK's own VERSION constant
# carries. CI runs this, so the README can never drift from the example.
#
# Usage:
#   scripts/check-quickstart.sh [--readme PATH]
#
# Prints EXAMPLE_MATCHES_README (exit 0) on equality plus INSTALL_BLOCKS_OK
# when the install blocks are valid, EXAMPLE_DIFFERS with a unified diff
# (exit 1) on drift, and EXAMPLE_MISSING (exit 1) when the markers or fences
# are unusable or the example is absent or not ASCII.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

README="$ROOT/README.md"
EXAMPLE="$ROOT/examples/Quickstart.java"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --readme)
      [ "$#" -ge 2 ] || { echo "--readme needs a path" >&2; exit 2; }
      README=$2
      shift 2
      ;;
    *)
      echo "usage: scripts/check-quickstart.sh [--readme PATH]" >&2
      exit 2
      ;;
  esac
done

START='<!-- docql:quickstart:start -->'
END='<!-- docql:quickstart:end -->'
MAVEN_START='<!-- docql:install-maven:start -->'
MAVEN_END='<!-- docql:install-maven:end -->'
GRADLE_START='<!-- docql:install-gradle:start -->'
GRADLE_END='<!-- docql:install-gradle:end -->'

missing() {
  echo "EXAMPLE_MISSING"
  exit 1
}

# The version the SDK itself carries, so the blocks can never name one it
# does not.
VERSION=$(sed -n 's/^.*String VERSION = "\([^"]*\)".*/\1/p' \
  "$ROOT/src/main/java/com/finoniq/docql/DocQL.java" | head -n 1)
[ -n "$VERSION" ] || { echo "cannot read VERSION from DocQL.java" >&2; exit 2; }

[ -f "$EXAMPLE" ] || missing

# The example must be ASCII: the source launcher reads it in the default
# charset, which a non-UTF-8 locale mis-decodes. A plain bracket expression
# over bytes 0x80-0xFF in the C locale works on BSD and GNU grep alike (no
# PCRE -P). grep's own error (status 2) fails closed instead of passing.
ascii_rc=0
LC_ALL=C grep -q "$(printf '[^\001-\177]')" "$EXAMPLE" || ascii_rc=$?
case "$ascii_rc" in
  0)
    echo "EXAMPLE_MISSING (examples/Quickstart.java is not ASCII)"
    exit 1
    ;;
  1) ;;
  *)
    echo "cannot check examples/Quickstart.java for non-ASCII bytes (grep exit $ascii_rc)" >&2
    exit 2
    ;;
esac

# Marker rules: each marker exactly once as a whole line, start before end.
starts=$(grep -cFx "$START" "$README" || true)
ends=$(grep -cFx "$END" "$README" || true)
[ "$starts" -eq 1 ] && [ "$ends" -eq 1 ] || missing
sline=$(grep -nFx "$START" "$README" | cut -d: -f1)
eline=$(grep -nFx "$END" "$README" | cut -d: -f1)
[ "$sline" -lt "$eline" ] || missing

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# Fence rules: exactly one line equal to ```java and one line equal to ```
# strictly between the markers, open before close, block non-empty.
awk -v s="$sline" -v e="$eline" 'NR > s && NR < e && $0 == "```java" { print NR }' "$README" > "$TMP/opens"
awk -v s="$sline" -v e="$eline" 'NR > s && NR < e && $0 == "```" { print NR }' "$README" > "$TMP/closes"
[ "$(wc -l < "$TMP/opens")" -eq 1 ] || missing
[ "$(wc -l < "$TMP/closes")" -eq 1 ] || missing
open=$(head -n 1 "$TMP/opens")
close=$(head -n 1 "$TMP/closes")
[ "$open" -lt "$close" ] || missing
[ "$((close - open))" -gt 1 ] || missing

sed -n "$((open + 1)),$((close - 1))p" "$README" > "$TMP/Quickstart.java"

if cmp -s "$TMP/Quickstart.java" "$EXAMPLE"; then
  echo "EXAMPLE_MATCHES_README"
else
  echo "EXAMPLE_DIFFERS"
  diff -u "$EXAMPLE" "$TMP/Quickstart.java" || true
  exit 1
fi

# Install blocks: each pair once, start before end, carrying the current
# version in the expected shape.
msline=$(grep -nFx "$MAVEN_START" "$README" | cut -d: -f1)
meline=$(grep -nFx "$MAVEN_END" "$README" | cut -d: -f1)
gsline=$(grep -nFx "$GRADLE_START" "$README" | cut -d: -f1)
geline=$(grep -nFx "$GRADLE_END" "$README" | cut -d: -f1)
[ "$(grep -cFx "$MAVEN_START" "$README")" -eq 1 ] && \
  [ "$(grep -cFx "$MAVEN_END" "$README")" -eq 1 ] && \
  [ "$(grep -cFx "$GRADLE_START" "$README")" -eq 1 ] && \
  [ "$(grep -cFx "$GRADLE_END" "$README")" -eq 1 ] || missing
[ -n "$msline" ] && [ -n "$meline" ] && [ "$msline" -lt "$meline" ] || missing
[ -n "$gsline" ] && [ -n "$geline" ] && [ "$gsline" -lt "$geline" ] || missing

sed -n "$((msline + 1)),$((meline - 1))p" "$README" > "$TMP/maven.txt"
sed -n "$((gsline + 1)),$((geline - 1))p" "$README" > "$TMP/gradle.txt"

grep -q '<artifactId>docql</artifactId>' "$TMP/maven.txt" || missing
grep -q "<version>$VERSION</version>" "$TMP/maven.txt" || missing
grep -qF "implementation(\"com.finoniq:docql:$VERSION\")" "$TMP/gradle.txt" || missing

echo "INSTALL_BLOCKS_OK"
