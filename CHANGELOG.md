# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.1.0]: https://github.com/finoniq/docql-java/tree/v0.1.0
