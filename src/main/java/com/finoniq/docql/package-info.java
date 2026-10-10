/**
 * DocQL Java SDK — a client for the DocQL document-extraction API
 * ({@code POST /v1/query-document}).
 *
 * <p>Send one document with an instruction and get its extraction result:
 *
 * <pre>{@code
 * DocQL client = DocQL.builder()
 *     .apiKey("dql_...")
 *     .apiUrl("https://api-docql.finoniq.com")
 *     .build();
 * QueryResult result = client.queryDocument(
 *     DocFile.bytes(data, "invoice.pdf"),
 *     Instruction.query("What is the total amount?"));
 * }</pre>
 *
 * <p>A {@link com.finoniq.docql.DocQL} client is immutable and thread-safe.
 * It needs no closing.
 *
 * <p>Only the public types of this package are API. The package-private
 * helpers are implementation details with no compatibility promise.
 */
package com.finoniq.docql;
