#!/usr/bin/env bash
# scripts/run-readme.sh — run the README quickstart against the pinned stub.
#
# Extracts the single fenced Java block between the docql:quickstart markers
# of README.md, writes it as Quickstart.java into a fresh temporary directory
# next to invoice.pdf (the golden kit's ascii-query document), and runs it
# there with the JDK source launcher against the built jar plus its runtime
# dependency classpath, with DOCQL_API_KEY and DOCQL_API_URL set explicitly
# to the kit key and the stub URL — the developer's own environment is never
# read or printed. CI and the release verify job run this script unchanged.
#
# Usage:
#   scripts/stub-up.sh --run scripts/run-readme.sh [--readme PATH] [--classpath CP]
#
# Requires DOCQL_STUB_URL. The default classpath is target/docql-<VERSION>.jar
# plus the runtime scope built with the maven-dependency-plugin;
# --classpath CP replaces it entirely.
#
# Prints README_QUICKSTART_OK on success and README_QUICKSTART_MISSING when a
# marker or the fenced block is unusable. Exits non-zero on any failure.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

README="$ROOT/README.md"
CP=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --readme)
      [ "$#" -ge 2 ] || { echo "--readme needs a path" >&2; exit 2; }
      README=$2
      shift 2
      ;;
    --classpath)
      [ "$#" -ge 2 ] || { echo "--classpath needs a value" >&2; exit 2; }
      CP=$2
      shift 2
      ;;
    *)
      echo "usage: scripts/run-readme.sh [--readme PATH] [--classpath CP]" >&2
      exit 2
      ;;
  esac
done

STUB_URL=${DOCQL_STUB_URL:-}
if [ -z "$STUB_URL" ]; then
  echo "DOCQL_STUB_URL is not set: boot the pinned stub (scripts/stub-up.sh)" >&2
  exit 1
fi

START='<!-- docql:quickstart:start -->'
END='<!-- docql:quickstart:end -->'

missing() {
  echo "README_QUICKSTART_MISSING" >&2
  exit 1
}

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

# invoice.pdf is the golden kit's ascii-query document; the key comes from
# the vendored case list, so the run needs no real credential.
jq -r '.cases[] | select(.id == "ascii-query") | .fileBase64' "$ROOT/contract/golden.json" | base64 -d > "$TMP/invoice.pdf"
API_KEY=$(jq -r '.keys.valid' "$ROOT/contract/sdk-cases.json")

if [ -z "$CP" ]; then
  VERSION=$(sed -n 's/^.*String VERSION = "\([^"]*\)".*/\1/p' \
    "$ROOT/src/main/java/com/finoniq/docql/DocQL.java" | head -n 1)
  [ -n "$VERSION" ] || { echo "cannot read VERSION from DocQL.java" >&2; exit 2; }
  JAR="$ROOT/target/docql-$VERSION.jar"
  [ -f "$JAR" ] || { echo "$JAR is missing: build it first (./mvnw -B -ntp -DskipTests package)" >&2; exit 1; }
  (cd "$ROOT" && ./mvnw -B -ntp -q dependency:build-classpath \
    -Dmdep.includeScope=runtime -Dmdep.outputFile="$TMP/cp.txt") > /dev/null
  CP="$JAR:$(cat "$TMP/cp.txt")"
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA="${JAVA_HOME}/bin/java"
else
  JAVA=java
fi

export DOCQL_API_KEY="$API_KEY"
export DOCQL_API_URL="$STUB_URL"

set +e
out=$(cd "$TMP" && timeout 120 "$JAVA" -cp "$CP" Quickstart.java 2>"$TMP/quickstart.stderr")
status=$?
set -e
if [ "$status" -ne 0 ]; then
  cat "$TMP/quickstart.stderr" >&2
  echo "the README quickstart failed (exit $status)" >&2
  exit 1
fi
if [ -n "$out" ]; then
  printf '%s\n' "$out"
fi
echo "README_QUICKSTART_OK"
