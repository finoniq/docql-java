# DocQL Java SDK

A Java client for the DocQL document-extraction API (`POST /v1/query-document`).
Maven coordinates: `com.finoniq:docql`. The only dependency is Jackson
(`jackson-databind`), because `JsonNode` is part of the public result type.

## Install

Maven:

<!-- docql:install-maven:start -->
```xml
<dependency>
  <groupId>com.finoniq</groupId>
  <artifactId>docql</artifactId>
  <version>0.1.0</version>
</dependency>
```
<!-- docql:install-maven:end -->

Gradle (Kotlin DSL):

<!-- docql:install-gradle:start -->
```kotlin
implementation("com.finoniq:docql:0.1.0")
```
<!-- docql:install-gradle:end -->

## Requirements

- Java 17 or newer.
- Jackson 2.21.x (`jackson-databind`) — added transitively; you do not add it
  yourself.

## Quickstart

Set `DOCQL_API_KEY`, and set `DOCQL_API_URL` to the API origin you were given — the production endpoint `https://api-docql.finoniq.com` is not live yet. Save the program below as `Quickstart.java`, put an `invoice.pdf` in the working directory, and run it with the JDK source launcher:

<!-- docql:quickstart:start -->
```java
// The README quickstart, kept byte-identical with the README's fenced block
// by scripts/check-quickstart.sh. Set DOCQL_API_KEY, point DOCQL_API_URL at
// the API origin you were given, put an invoice.pdf in the working directory,
// and run it with the JDK source launcher:
//
//   java -cp docql-0.1.0.jar:jackson-databind-2.21.7.jar:jackson-core-2.21.7.jar:jackson-annotations-2.21.jar Quickstart.java

import java.nio.file.Path;

import com.finoniq.docql.DocFile;
import com.finoniq.docql.DocQL;
import com.finoniq.docql.DocQLConnectionException;
import com.finoniq.docql.DocQLException;
import com.finoniq.docql.Instruction;
import com.finoniq.docql.QueryResult;

public class Quickstart {

    public static void main(String[] args) {
        DocQL client = DocQL.builder().build(); // reads DOCQL_API_KEY and DOCQL_API_URL
        try {
            QueryResult res = client.queryDocument(
                    DocFile.path(Path.of("invoice.pdf")),
                    Instruction.query("{ invoice_number total_amount }"));
            System.out.println(res.data());
            System.out.println("request id: " + res.requestId());
            if (res.pageCount() != null) {
                System.out.println("pages: " + res.pageCount());
            }
        } catch (DocQLException e) {
            System.err.println("status " + e.status()
                    + (e.reason() != null ? " reason " + e.reason() : "")
                    + (e.requestId() != null ? " request id " + e.requestId() : ""));
            System.exit(1);
        } catch (DocQLConnectionException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
```
<!-- docql:quickstart:end -->

The same file is [`examples/Quickstart.java`](examples/Quickstart.java). The
query uses DocQL's brace-and-field syntax; pass `Instruction.prompt(...)`
instead for a natural-language instruction — a call takes exactly one
`Instruction.query` or `Instruction.prompt`, enforced by the type system.
`pageCount()` is `null` when the API did not report pages, so guard it before
unboxing, like the example does.

### Async calls

`queryDocumentAsync(...)` has the same arguments and returns a
`CompletableFuture<QueryResult>` built directly on the JDK's
`HttpClient.sendAsync`; the SDK owns no thread pool and has no executor
option:

```java
CompletableFuture<QueryResult> future = client.queryDocumentAsync(file, instruction);
try {
    QueryResult res = future.get();
} catch (ExecutionException e) {
    DocQLException apiErr = (DocQLException) e.getCause(); // the same error the sync call throws
}
```

Errors complete the future exceptionally with the very
`DocQLException` / `DocQLConnectionException` / `DocQLTimeoutException`
instances the synchronous call throws — surfacing as the cause of
`ExecutionException` from `get()` and `CompletionException` from `join()`.
`null` arguments throw `NullPointerException` immediately; every other
pre-send failure completes the future exceptionally. Cancelling the returned
future cancels the underlying exchange, and the client stays usable.

## Configuration

`DocQL.builder()` carries the values below; a built client is immutable and
safe for concurrent use — create one and reuse it. It needs no closing.

| Builder method | Env variable | Default | Notes |
| --- | --- | --- | --- |
| `apiKey(key)` | `DOCQL_API_KEY` | none | required; `build()` throws when neither is set |
| `apiUrl(url)` | `DOCQL_API_URL` | `https://api-docql.finoniq.com` | trailing `/` ignored; `http://` allowed for local stacks; the default is not live yet, so pass `apiUrl` until production DNS exists |
| `timeout(d)` | — | 660 s | the per-request deadline: it covers the upload until the response headers arrive, so on a slow uplink a large file uses part of it |
| `httpClient(hc)` | — | none | an injected `HttpClient` must use `Redirect.NEVER`, and `build()` fails fast otherwise |

Explicit builder values win over the environment variables; a whitespace-only
environment variable counts as unset. The production default URL needs
`apiUrl` until it exists.

**Local stacks behind Caddy `tls internal`:** the JVM trust store does not
know the local CA. Import the exported root certificate into a trust store
and pass it with `-Djavax.net.ssl.trustStore=<path>`
(`-Djavax.net.ssl.trustStorePassword=<password>`), or inject an
`HttpClient` whose `SSLContext` trusts the local CA. Never turn certificate
verification off.

**Redirects:** an injected client must use `HttpClient.Redirect.NEVER` — the
JDK strips only `Authorization` on cross-host redirects, not your
`X-API-Key`, so `build()` rejects a client that follows redirects. The
SDK-built client never follows redirects.

**Plain `http://`:** the key travels in a header in clear text. Use
`http://` only against a local stub or a loopback stack — never across a
network.

## Files

`queryDocument` takes one `DocFile`, built with `DocFile.path(Path)`,
`DocFile.bytes(byte[] data, String filename)` or
`DocFile.stream(InputStream in, long length, String filename)`:

- The filename rule: a `QueryOptions.filename(...)` override is sent
  verbatim; otherwise a path input uses its base name; otherwise the `name`
  argument of the factory is used. There is no default filename — when the
  final name would be empty, the call fails with
  `IllegalArgumentException` before anything is sent.
- A path input must be a regular file; the check happens before any byte is
  sent. Its body streams from disk, never fully buffered.
- A stream must have a known length, which keeps the multipart body an exact
  `Content-Length`. The SDK reads it from its current position, reads at
  most `length` bytes, and never closes it; a stream that ends early fails
  the call instead of undersending silently.
- There are no client-side type or size checks — the server is the
  authority. The API rejects files over 25 MiB, and a transport failure on a
  larger known-length upload carries a size hint in its message.

## Modes

`Mode.FAST` and `Mode.STANDARD` name the two processing modes.
`Mode.of(name)` builds any other value: `Mode` is open, unknown values pass
through and the server validates them. Without a mode, `params.mode` is
omitted from the request.

## Errors

Two unchecked kinds, with no shared SDK base class — catch both with a
multi-catch:

- `DocQLException` — the server answered non-2xx (including 3xx, which the
  SDK never follows, and a 2xx that is not a usable DocQL answer). It
  carries `status()`, `reason()` (null for a non-DocQL body), `requestId()`
  (null when absent), `retryAfter()` (`Duration`, null when absent),
  `retryable()` and a key-scrubbed `body()` truncated to 2048 characters.
- `DocQLConnectionException` — no usable answer arrived (connect, TLS,
  reset, broken pipe, short read). `DocQLTimeoutException` extends it and
  reports an expired deadline.

Messages follow two forms: `"<status> <reason>: <message> (request_id=…,
retry_after=Ns)"` for a DocQL envelope and `"<status> (non-DocQL response):
<snippet>"` for anything else. The API key never appears in any message, in
`body()` or in `toString()`.

Every reason below is exported as a `Reasons` constant
(`invalid_api_key` is `Reasons.INVALID_API_KEY`):

| Status | Reason | What to do |
| --- | --- | --- |
| 401 | `Reasons.INVALID_API_KEY` (`invalid_api_key`) | the key is wrong or revoked — check `DOCQL_API_KEY` or issue a new key |
| 413 | `Reasons.UPLOAD_TOO_LARGE` (`upload_too_large`) | the file exceeds the API limit (25 MiB) — shrink or split it |
| 415 | `Reasons.UNSUPPORTED_MEDIA_TYPE` (`unsupported_media_type`) | the request was not multipart/form-data — send it through `queryDocument` |
| 422 | `Reasons.MISSING_FILE_PART` (`missing_file_part`) | the multipart body has no file part |
| 422 | `Reasons.MISSING_BODY_FIELD` (`missing_body_field`) | the multipart body has no `body` form field |
| 422 | `Reasons.BODY_NOT_JSON` (`body_not_json`) | the `body` form field is not valid JSON |
| 422 | `Reasons.MISSING_QUERY_OR_PROMPT` (`missing_query_or_prompt`) | pass exactly one of `Instruction.query` or `Instruction.prompt` |
| 422 | `Reasons.INVALID_PARAMS` (`invalid_params`) | check the parameters — for example an unknown `Mode` |
| 422 | `Reasons.UNSUPPORTED_FILE_TYPE` (`unsupported_file_type`) | the API does not accept this file type |
| 422 | `Reasons.CORRUPTED_FILE` (`corrupted_file`) | the file could not be parsed |
| 422 | `Reasons.DOCUMENT_EXCEEDS_TIME_BUDGET` (`document_exceeds_time_budget`) | the document is too large for the mode — try `Mode.STANDARD` or split it |
| 429 | `Reasons.SERVICE_OVERLOADED` (`service_overloaded`) | retryable — wait `retryAfter()` seconds and retry |
| 502 | `Reasons.UPSTREAM_AUTH_FAILED` (`upstream_auth_failed`) | not retryable — quote the request id when contacting support |
| 503 | `Reasons.UPSTREAM_UNAVAILABLE` (`upstream_unavailable`) | retryable — wait `retryAfter()` seconds and retry |
| any | any other reason | read the message; only 429 and 503 are `retryable()` |
| any | a null `reason()` | a non-DocQL response: a framework 404/405 means a wrong `apiUrl`; an empty 5xx or an HTML 524 from an edge means the call may still have been processed |
| 3xx | — | the API URL redirects; the SDK never follows redirects, so set `apiUrl` to the final URL |

## Retrying

The SDK never retries — every call is billed. Retry yourself, only when the
caught `DocQLException` reports `retryable()` true, sleeping
`retryAfter()` when it is greater than zero and a capped exponential backoff
otherwise, with at most 5 attempts:

```java
static QueryResult queryWithRetry(DocQL client, DocFile file, Instruction instr) throws Exception {
    for (int attempt = 1; ; attempt++) {
        try {
            return client.queryDocument(file, instr);
        } catch (DocQLConnectionException transport) {
            throw transport; // transport failures are not retry signals
        } catch (DocQLException e) {
            if (attempt >= 5 || !e.retryable()) {
                throw e;
            }
            long waitSeconds = e.retryAfter() != null ? e.retryAfter().toSeconds() : 0;
            if (waitSeconds <= 0) {
                waitSeconds = Math.min(1L << attempt, 30);
            }
            Thread.sleep(Duration.ofSeconds(waitSeconds).toMillis());
        }
    }
}
```

## Timeouts and billing

Usage is metered in pages. The 660 s deadline **includes the upload**: the
request deadline runs until the response headers arrive, so a large file on
a slow uplink uses part of it (the Python SDK instead applies a per-read
inactivity timeout). A timed-out call may still have been billed — the
server may finish the work after the client gave up. For the same reason,
502/504/524 answers are not retryable: only 429 and 503 mean the request was
not processed.

## Versioning

The SDK follows [Semantic Versioning](https://semver.org/). While the major
version is 0, a minor release may change the API; pin `0.1.0` until 1.0.
Maven Central releases are permanent: a bad release is never replaced — it
is fixed in a higher version.

## Support

When asking for help, quote the request id — `res.requestId()` on success,
`requestId()` of a caught `DocQLException` on failure. Never share your API
key. Report security issues through [SECURITY.md](SECURITY.md).

## License

Apache-2.0 — see [LICENSE](LICENSE).
