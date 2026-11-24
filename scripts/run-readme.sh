#!/usr/bin/env bash
# scripts/run-readme.sh — run a README example (the quickstart, the jobs
# example, the spreadsheet example or the webhook receiver) against the
# pinned stub.
#
# Extracts the single fenced Java block between the docql:quickstart markers
# (or, with --block jobs / --block spreadsheet / --block webhook, the
# docql:jobs / docql:spreadsheet / docql:webhook markers) of README.md, writes
# it as Quickstart.java (Jobs.java, Spreadsheet.java, Webhook.java) into a
# fresh temporary directory of its own
# next to invoice.pdf (the golden kit's ascii-query document) and sales.csv
# (a copy of the kit's contract/sheets/plain.csv), and runs it
# there with the JDK source launcher against the built jar plus its runtime
# dependency classpath, with DOCQL_API_KEY and DOCQL_API_URL set explicitly
# to the kit key and the stub URL — the developer's own environment is never
# read or printed. CI and the release verify job run this script unchanged.
#
# The webhook block (D-23) is a receiver that serves until it is stopped. The
# script starts it with the source launcher on a free loopback port
# (18300..18399) with a whsec_ secret made for this run (never printed), POSTs
# one delivery signed at run time with an openssl HMAC and expects 2xx plus
# the line `event job.succeeded <id>`, then POSTs the same headers with one
# body byte changed and expects 400 and no second line. It needs openssl, od
# and curl.
#
# Usage:
#   scripts/stub-up.sh --run scripts/run-readme.sh [--readme PATH] [--classpath CP]
#       [--block quickstart|jobs|spreadsheet|webhook]
#
# --block defaults to quickstart.
#
# Requires DOCQL_STUB_URL. The default classpath is target/docql-<VERSION>.jar
# plus the runtime scope built with the maven-dependency-plugin;
# --classpath CP replaces it entirely.
#
# Prints README_QUICKSTART_OK (README_JOBS_OK for --block jobs,
# README_SPREADSHEET_OK for --block spreadsheet, README_WEBHOOK_OK for --block
# webhook) on success and README_QUICKSTART_MISSING (README_JOBS_MISSING,
# README_SPREADSHEET_MISSING, README_WEBHOOK_MISSING) when a marker or the
# fenced block is unusable; README_WEBHOOK_FAIL <problem> when the receiver
# misbehaves. Exits non-zero on any failure.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$SCRIPT_DIR/.." && pwd)

README="$ROOT/README.md"
CP=""
BLOCK=quickstart
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
    --block)
      [ "$#" -ge 2 ] || { echo "--block needs quickstart, jobs, spreadsheet or webhook" >&2; exit 2; }
      case "$2" in
        quickstart|jobs|spreadsheet|webhook) BLOCK=$2 ;;
        *) echo "--block needs quickstart, jobs, spreadsheet or webhook" >&2; exit 2 ;;
      esac
      shift 2
      ;;
    *)
      echo "usage: scripts/run-readme.sh [--readme PATH] [--classpath CP] [--block quickstart|jobs|spreadsheet|webhook]" >&2
      exit 2
      ;;
  esac
done

STUB_URL=${DOCQL_STUB_URL:-}
if [ -z "$STUB_URL" ]; then
  echo "DOCQL_STUB_URL is not set: boot the pinned stub (scripts/stub-up.sh)" >&2
  exit 1
fi

case "$BLOCK" in
  quickstart)
    FILE=Quickstart.java
    TAG=README_QUICKSTART
    LABEL=quickstart
    ;;
  jobs)
    FILE=Jobs.java
    TAG=README_JOBS
    LABEL="jobs example"
    ;;
  spreadsheet)
    FILE=Spreadsheet.java
    TAG=README_SPREADSHEET
    LABEL="spreadsheet example"
    ;;
  webhook)
    FILE=Webhook.java
    TAG=README_WEBHOOK
    LABEL="webhook example"
    ;;
esac
START="<!-- docql:$BLOCK:start -->"
END="<!-- docql:$BLOCK:end -->"

missing() {
  echo "${TAG}_MISSING" >&2
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
RECEIVER_PID=""
cleanup() {
  if [ -n "$RECEIVER_PID" ]; then
    kill "$RECEIVER_PID" 2>/dev/null || true
    wait "$RECEIVER_PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

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

sed -n "$((open + 1)),$((close - 1))p" "$README" > "$TMP/$FILE"

# invoice.pdf is the golden kit's ascii-query document; the key comes from
# the vendored case list, so the run needs no real credential.
jq -r '.cases[] | select(.id == "ascii-query") | .fileBase64' "$ROOT/contract/golden.json" | base64 -d > "$TMP/invoice.pdf"
# sales.csv is the kit's plain CSV, for the spreadsheet example.
cp "$ROOT/contract/sheets/plain.csv" "$TMP/sales.csv"
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

# The webhook receiver (D-23): serve it in the background with the source
# launcher, send one delivery signed now and one with a changed body byte.
WEBHOOK_EVENT_ID=5f0c3a1e-45a1-4d1e-9b00-000000000451
webhook_fail() {
  cat "$TMP/receiver.out" "$TMP/receiver.err" >&2 2>/dev/null || true
  echo "README_WEBHOOK_FAIL $1"
  exit 1
}
webhook_post() { # webhook_post <body file>: prints the HTTP status, 000 when no answer
  curl -q -sS --noproxy '*' --max-time 10 -o /dev/null -w '%{http_code}' \
    -H 'content-type: application/json' \
    -H "webhook-id: $WEBHOOK_EVENT_ID" \
    -H "webhook-timestamp: $ts" \
    -H "webhook-signature: v1,$sig" \
    --data-binary "@$1" "http://127.0.0.1:$port/" 2>>"$TMP/curl.err" || true
}
run_webhook() {
  local secret p ready hex sent_at body tampered code expected
  # A secret in the console's text format, made for this run and never printed.
  secret="whsec_$(openssl rand 32 | openssl base64 -A)"
  port=""
  for p in $(seq 18300 18399); do
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then
      port=$p
      break
    fi
  done
  [ -n "$port" ] || webhook_fail "no free loopback port in 18300..18399"
  : >"$TMP/receiver.out"
  (cd "$TMP" && export DOCQL_WEBHOOK_SECRET="$secret" PORT="$port" && exec timeout 120 "$JAVA" -cp "$CP" "$FILE") \
    >"$TMP/receiver.out" 2>"$TMP/receiver.err" &
  RECEIVER_PID=$!
  # The source launcher compiles the file first, so allow it 30 s to listen.
  ready=0
  for _ in $(seq 1 60); do
    kill -0 "$RECEIVER_PID" 2>/dev/null || webhook_fail "the receiver exited before it listened"
    if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      ready=1
      break
    fi
    sleep 0.5
  done
  [ "$ready" -eq 1 ] || webhook_fail "the receiver did not listen on 127.0.0.1:$port within 30 s"

  # A job.succeeded body in the frozen shape, signed now: HMAC-SHA256 over
  # "<id>.<timestamp>.<body>" with the decoded key (hexkey, as the key is binary).
  ts=$(date +%s)
  sent_at=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
  body='{"id":"'"$WEBHOOK_EVENT_ID"'","type":"job.succeeded","timestamp":"'"$sent_at"'","data":{"job_id":"job_readme_7Hk2mQ9x","status":"succeeded"}}'
  tampered=${body/'"status":"succeeded"'/'"status":"succeedec"'}
  [ "$tampered" != "$body" ] || webhook_fail "could not change a byte of the delivery"
  hex=$(printf '%s' "${secret#whsec_}" | openssl base64 -d -A | od -An -v -tx1 | tr -d ' \n')
  [ "${#hex}" -eq 64 ] || webhook_fail "the run-time key is not 32 bytes"
  sig=$(printf '%s' "$WEBHOOK_EVENT_ID.$ts.$body" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$hex" -binary | openssl base64 -A)
  printf '%s' "$body" >"$TMP/delivery.json"
  printf '%s' "$tampered" >"$TMP/tampered.json"

  expected="event job.succeeded $WEBHOOK_EVENT_ID"
  code=$(webhook_post "$TMP/delivery.json")
  case "$code" in
    2??) ;;
    *) webhook_fail "the signed delivery got HTTP $code" ;;
  esac
  for _ in $(seq 1 20); do
    grep -qxF "$expected" "$TMP/receiver.out" && break
    sleep 0.5
  done
  grep -qxF "$expected" "$TMP/receiver.out" || webhook_fail "no line '$expected' for the signed delivery"

  code=$(webhook_post "$TMP/tampered.json")
  [ "$code" = 400 ] || webhook_fail "the tampered delivery got HTTP $code"
  sleep 0.5
  [ "$(wc -l <"$TMP/receiver.out")" -eq 1 ] || webhook_fail "the receiver printed more than the one event line"
  out=$(cat "$TMP/receiver.out")
}

if [ "$BLOCK" = webhook ]; then
  run_webhook
else
  set +e
  out=$(cd "$TMP" && timeout 120 "$JAVA" -cp "$CP" "$FILE" 2>"$TMP/example.stderr")
  status=$?
  set -e
  if [ "$status" -ne 0 ]; then
    cat "$TMP/example.stderr" >&2
    echo "the README $LABEL failed (exit $status)" >&2
    exit 1
  fi
fi
if [ -n "$out" ]; then
  printf '%s\n' "$out"
fi
echo "${TAG}_OK"
