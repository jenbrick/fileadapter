package com.jenbrick.fileadapter.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;
import java.net.http.HttpClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class HttpUpsertClient implements UpsertClient {
    private final RestClient.Builder builder;
    private final String bearerToken;
    private final ConcurrentMap<Delivery, RestClient> clients = new ConcurrentHashMap<>();

    public HttpUpsertClient(RestClient.Builder builder,
                            @Value("${upsert.bearer-token:}") String bearerToken) {
        this.builder = builder;
        this.bearerToken = bearerToken;
    }

    @Override
    public DeliveryResult post(JsonNode request, String idempotencyKey, Delivery settings) {
        var client = clients.computeIfAbsent(settings, config -> {
            var http = HttpClient.newBuilder().connectTimeout(config.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            var factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(config.readTimeout());
            return builder.clone().requestFactory(factory).build();
        });
        var spec = client.post().uri(settings.endpoint()).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (!bearerToken.isBlank()) spec.headers(h -> h.setBearerAuth(bearerToken));
        if (settings.idempotencyHeader() != null && !settings.idempotencyHeader().isBlank()) {
            spec.header(settings.idempotencyHeader(), idempotencyKey);
        }
        try {
            return spec.body(request).exchange((sent, response) -> {
                int status = response.getStatusCode().value();
                // Do not persist response bodies: they may echo sensitive input values.
                if (settings.successStatuses().contains(status)) {
                    return new DeliveryResult(Outcome.ACKNOWLEDGED, status);
                }
                if (status >= 200 && status < 300) {
                    return new DeliveryResult(Outcome.UNCERTAIN, status);
                }
                if (status == 408 || status == 429 || status >= 500) {
                    return new DeliveryResult(Outcome.RETRYABLE, status);
                }
                return new DeliveryResult(Outcome.REJECTED, status);
            });
        } catch (ResourceAccessException e) {
            return new DeliveryResult(Outcome.UNCERTAIN, null);
        }
    }
}
