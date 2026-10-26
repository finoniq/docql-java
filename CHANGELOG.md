# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.2.0]: https://github.com/finoniq/docql-java/tree/v0.2.0
[0.1.0]: https://github.com/finoniq/docql-java/tree/v0.1.0
