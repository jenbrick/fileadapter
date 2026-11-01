package com.jenbrick.fileadapter.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;

public interface UpsertClient {
    DeliveryResult post(JsonNode request, String idempotencyKey, Delivery settings);

    record DeliveryResult(Outcome outcome, Integer httpStatus, java.util.List<ServiceIssue> issues) {
        public DeliveryResult(Outcome outcome,Integer httpStatus) { this(outcome,httpStatus,java.util.List.of()); }
    }
    record ServiceIssue(com.jenbrick.fileadapter.results.ErrorPolicy.Severity severity,
                        com.jenbrick.fileadapter.results.ErrorPolicy.Scope scope,
                        String contractId,String memberGuid,String code) {}
    enum Outcome { ACKNOWLEDGED, RETRYABLE, REJECTED, UNCERTAIN }
}
