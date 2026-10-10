package com.finoniq.docql;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The successful answer of a queryDocument call (D-04).
 *
 * @param data the envelope's data object, always a JSON object; absent or
 *        null data arrives as an empty object
 * @param requestId the request id from the metadata, else the
 *        {@code x-request-id} response header; {@code ""} when neither is
 *        present
 * @param pageCount the page count from the metadata; null when absent or not
 *        a JSON integer — absent and 0 stay distinct, which page-based
 *        billing needs
 * @param pagesOcr the OCR page count from the metadata; null under the same
 *        rule as {@code pageCount}
 * @param metadata the full raw metadata object (empty when absent), so new
 *        engine fields reach callers without an SDK release
 */
public record QueryResult(
        JsonNode data,
        String requestId,
        Integer pageCount,
        Integer pagesOcr,
        JsonNode metadata) {
}
