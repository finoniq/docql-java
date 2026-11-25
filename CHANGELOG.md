# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- The README's webhook rotation guidance now says to remove the old secret
  from the list when the overlap ends, and at once after a roll caused by a
  leak, because a secret you still pass still verifies. This README change is
  on `main` only: no release carries it, and 0.4.0 stays the current release.

## [0.4.0] - 2026-11-24

### Added

- `Webhooks.verify(byte[] body, Map<String, List<String>> headers,
  List<String> secrets)` verifies a DocQL webhook delivery, signed as the open
  Standard Webhooks spec describes, and returns a `WebhookEvent`. Pass the raw
  request bytes before any JSON parsing, the request headers as they are and
  the endpoint's secret, or several secrets while a rotation overlaps: a
  delivery signed with any of them verifies. It needs no client and no API
  key. The overload `verify(body, headers, secrets, Duration tolerance)` sets
  the allowed clock difference either way (default 300 seconds).
- `WebhookEvent`, a final class with `id()`, `type()`, `timestamp()` (an
  `Instant`), `jobId()`, `status()` and `data()` (a `JsonNode`).
- `WebhookEventTypes` constants `JOB_SUCCEEDED`, `JOB_FAILED`,
  `JOB_CANCELLED` and `WEBHOOK_TEST`.
- `WebhookVerificationException`, thrown for a refused delivery. Its
  `reason()` is one of `missing_header`, `invalid_timestamp`,
  `invalid_signature_header`, `signature_mismatch`, `timestamp_too_old`,
  `timestamp_too_new` or `invalid_payload`. A null or malformed secret, an
  empty secret list or a tolerance of zero or less is an
  `IllegalArgumentException` whose message never shows the secret.
- README § Webhooks, also `examples/Webhook.java`: a receiver built on
  `com.sun.net.httpserver`, run against a delivery signed at run time.

### Fixed

- `DocQLException.metadata()` is never null, also for an exception serialized
  by 0.2.0 and read by this version. 0.3.0 returned null in that case, which
  its own entry below said could not happen; it now gives an empty object.

### Changed

- The README spreadsheet example, also `examples/Spreadsheet.java`, picks the
  axis by name (`cells`, `runes`, `estimated_output_tokens` or `bytes`)
  instead of taking the first metadata field that is not `limit`, `reason`
  or `request_id`.
- Contract kit v6 is vendored (stub pin `sha-05db33e`). Its success bodies no
  longer carry `metadata.screenshot`, and the SDK reads a success with or
  without that key the same way.
- SECURITY.md names the 0.4.x line as the supported release line, and the
  README install blocks, quickstart classpath and § Versioning name 0.4.

No breaking changes: code compiled against 0.3.0 links and runs unchanged.

## [0.3.0] - 2026-11-23

Java goes from 0.2.0 straight to 0.3.0, so the Python, Go and Java SDKs share
the 0.3.0 version. There is no 0.2.1 for Java.

### Added

- `DocQLException.metadata()`: the server's raw refusal metadata object of an
  error answer, as a `JsonNode`, with the API key scrubbed from field names
  and text values. It is never null: an answer without a metadata object
  gives an empty object. Numbers stay exact (`asLong()`, `bigIntegerValue()`).
  It is filled on every route, the jobs calls and a failed job's `error()`
  included, and survives Java serialization. `getMessage()` is unchanged.
- Three `Reasons` constants: `SPREADSHEET_TOO_LARGE`, `DOCUMENT_TOO_COMPLEX`
  and `TOO_MANY_PAGES` (25 in all).
- A README spreadsheet example, also `examples/Spreadsheet.java`: it sends a
  CSV by an explicit `.csv` name with `QueryOptions.builder().filename(...)`
  and reads the axis, its value and the limit of a `spreadsheet_too_large`
  refusal from `metadata()`.
- README § Files states the CSV file-name rule: a CSV is recognised only by
  a name ending in `.csv` in any letter case, `DocFile.bytes` and
  `DocFile.stream` need the name, and the name is sent verbatim (`"a.csv "`
  is refused with `unsupported_file_type`).

### Changed

- Contract kit v5 is vendored (stub pin `sha-1a3c3ce`); the contract suite
  runs all 26 spreadsheet cases on the sync and the jobs route.
- SECURITY.md names the 0.3.x line as the supported release line, and the
  README install blocks, quickstart classpath and § Versioning name 0.3.
- README § Errors lists `metadata()`.

No breaking changes: code compiled against 0.2.0 links and runs unchanged.

## [0.2.0] - 2026-10-26

### Added

- Async jobs for long documents: `submitDocument(DocFile, Instruction[,
  QueryOptions])`, `getJob(jobId)`, `waitFor(jobId[, WaitOptions])` and
  `cancelJob(jobId)`. `waitFor` is named so because `wait` is final on
  `Object`.
- `Job` (a final class) with `jobId()`, `status()`, `pageCount()`,
  `pagesDone()`, `requestId()`, the `createdAt()` / `startedAt()` /
  `finishedAt()` / `expiresAt()` timestamps, `result()` (a `QueryResult` on a
  succeeded job), `error()` (a `DocQLException` on a failed job),
  `retryAfter()` and `idempotencyKey()`; `toString()` never shows the key or
  the result data.
- `JobStatus` constants `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED` and
  `CANCELLED`; an unknown status from a newer API is kept verbatim.
- `WaitOptions.builder()` with `timeout` (15 minutes by default) and
  `pollInterval`, and `DocQLWaitTimeoutException`, a `DocQLTimeoutException`
  carrying `jobId()`.
- Eight new `Reasons` constants: `CONCURRENCY_LIMIT_EXCEEDED`,
  `RATE_LIMIT_EXCEEDED`, `JOB_NOT_FOUND`, `JOB_EXPIRED`, `QUEUE_FULL`,
  `IDEMPOTENCY_KEY_CONFLICT`, `IDEMPOTENCY_IN_PROGRESS` and
  `JOB_INTERRUPTED`.
- `DocQLException.jobId()` and `DocQLException.idempotencyKey()`, and
  `DocQLConnectionException.idempotencyKey()`.
- `QueryOptions.Builder#idempotencyKey(String)` for `submitDocument`;
  `queryDocument` throws `IllegalArgumentException` when it is set.
- Every submit carries an `Idempotency-Key`, generated per call (32 random
  bytes, base64url) when you set none. A 503 or a 409
  `idempotency_in_progress` on submit is retried with that same key, at most
  4 attempts, sleeping `Retry-After` clamped to 1-30 s or else 1, 2, then
  4 s, and only for input that can be sent again from its start
  (`DocFile.path`, `DocFile.bytes`, or a `DocFile.stream` whose stream
  supports mark/reset).
- `waitFor` sleeps the server's `Retry-After` between polls (clamped to
  1-30 s), retries network errors, poll timeouts, 429 and 503, and gives up
  with `DocQLWaitTimeoutException` after 15 minutes by default. It never sends
  a poll at or past its deadline: a sleep that ends exactly on the deadline
  ends the wait. It never cancels the job, not on a timeout and not on an
  interrupt (an interrupted wait throws `DocQLConnectionException` and sets
  the interrupt flag again).

### Changed

- Nothing breaking. 0.1.0's "nothing retries" now applies to `queryDocument`
  only: `submitDocument` retries as described above, and `queryDocument`,
  `getJob` and `cancelJob` each send exactly one request.

### Fixed

- `build()` rejects an API key with a character above U+00FF, before any
  request exists, with a message that names `DOCQL_API_KEY` and never echoes
  the key (13-REVIEW WR-01).
- CI derives the release jar names from `DocQL.VERSION` instead of a fixed
  `0.1.0` (13-REVIEW WR-02).
- `Retry-After` on every 429 and 503 answer is now pinned by tests against the
  contract kit (`retryAfter()` and `retryable()` on the decoded exception).
- `scripts/check-quickstart.sh` checks the examples for non-ASCII bytes in a
  way BSD and GNU grep both accept, and fails closed when grep errors
  (13-REVIEW IN-03).

No breaking changes: code compiled against 0.1.0 links and runs unchanged.

## [0.1.0] - 2026-10-10

### Added

- `DocQL.builder()` with `apiKey`, `apiUrl`, `timeout` and `httpClient`; the
  `DOCQL_API_KEY` / `DOCQL_API_URL` environment fallbacks (whitespace-only
  counts as unset), the 660 s per-request deadline that includes the upload,
  an injected `HttpClient` required to use `Redirect.NEVER`, and a masked
  `toString()`. A `DocQL` client is immutable and safe for concurrent use,
  and needs no closing.
- `queryDocument(DocFile, Instruction[, QueryOptions])` with the file
  factories `DocFile.path`, `DocFile.bytes` and `DocFile.stream` (strict
  filename rule, no default filename; known-length streams that are read
  from their current position and never closed), the compile-time
  `Instruction.query` / `Instruction.prompt` xor, and the per-call options
  `mode` and `filename`. Nothing retries — every call is billed.
- `queryDocumentAsync(...)` returning `CompletableFuture<QueryResult>`, built
  directly on `HttpClient.sendAsync` with no SDK-owned executor; errors
  complete the future with the same exceptions the sync call throws.
- `QueryResult` (a record) with `data`, `requestId`, `pageCount`, `pagesOcr`
  and the raw `metadata`; the page counts are `Integer`, so "absent" stays
  distinct from 0. The open `Mode` type with `Mode.FAST` / `Mode.STANDARD` /
  `Mode.of`, and the fourteen `Reasons` constants.
- `DocQLException` for every non-2xx answer (and an unusable 2xx) with
  `status()`, `reason()`, `requestId()`, `retryAfter()`, `retryable()` and a
  key-scrubbed `body()`; `DocQLConnectionException` for transport failures
  with a size hint on known-length uploads over 25 MiB and
  `DocQLTimeoutException` for an expired deadline. The API key never appears
  in any message, in `body()` or in `toString()`.

[Unreleased]: https://github.com/finoniq/docql-java/compare/v0.4.0...HEAD
[0.4.0]: https://github.com/finoniq/docql-java/tree/v0.4.0
[0.3.0]: https://github.com/finoniq/docql-java/tree/v0.3.0
[0.2.0]: https://github.com/finoniq/docql-java/tree/v0.2.0
[0.1.0]: https://github.com/finoniq/docql-java/tree/v0.1.0
