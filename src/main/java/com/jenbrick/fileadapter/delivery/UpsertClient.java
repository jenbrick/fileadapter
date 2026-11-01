package com.jenbrick.fileadapter.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;

/**
 * Keep HTTP delivery behind an interface so the rest of the pipeline does not need to know how the
 * request is sent.
 */
public interface UpsertClient {
    DeliveryResult post(JsonNode request, String idempotencyKey, Delivery settings);

    // Keep the outcome and any scoped service findings together so they can be saved before results
    // are published.
    record DeliveryResult(Outcome outcome, Integer httpStatus, java.util.List<ServiceIssue> issues) {
        public DeliveryResult(Outcome outcome,Integer httpStatus) { this(outcome,httpStatus,java.util.List.of()); }
    }
    // These IDs identify affected source rows; customer wording comes from approved configuration.
    record ServiceIssue(com.jenbrick.fileadapter.results.ErrorPolicy.Severity severity,
                        com.jenbrick.fileadapter.results.ErrorPolicy.Scope scope,
                        String contractId,String memberGuid,String code) {}
    enum Outcome { ACKNOWLEDGED, RETRYABLE, REJECTED, UNCERTAIN }
}
