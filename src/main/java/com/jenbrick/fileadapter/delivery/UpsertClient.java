package com.jenbrick.fileadapter.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;

public interface UpsertClient {
    DeliveryResult post(JsonNode request, String idempotencyKey, Delivery settings);

    record DeliveryResult(Outcome outcome, Integer httpStatus) {}
    enum Outcome { ACKNOWLEDGED, RETRYABLE, REJECTED, UNCERTAIN }
}
