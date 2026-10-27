package com.jenbrick.fileadapter.consent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.consent.ConsentSettings.Resolution;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP protocol here is an integration facade contract, not an assumed company API. */
@Component
public class ConfiguredMemberResolver implements MemberResolver {
    private final RestClient.Builder builder;
    private final String token;
    public ConfiguredMemberResolver(RestClient.Builder builder,
            @Value("${consent.lookup-bearer-token:}") String token) {
        this.builder = builder;
        this.token = token;
    }
    @Override public Resolution resolve(ObjectNode criteria, ConsentSettings settings) {
        var lookup = settings.lookup();
        if (lookup.mode() == ConsentSettings.LookupMode.FIXTURE) {
            var matches = lookup.fixtures().stream().filter(f -> f.match() != null && !f.match().isEmpty()
                    && f.match().entrySet().stream().allMatch(e ->
                        criteria.path(e.getKey()).asText().equals(e.getValue()))).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Lookup must resolve exactly one member");
            return matches.get(0).result();
        }
        var http = HttpClient.newBuilder().connectTimeout(lookup.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(lookup.readTimeout());
        var client = builder.clone().requestFactory(factory).build();
        var request = client.post().uri(lookup.endpoint()).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (!token.isBlank()) request.headers(h -> h.setBearerAuth(token));
        return request.body(criteria).exchange((sent, response) -> {
            if (response.getStatusCode().value() != 200) {
                throw new IllegalArgumentException("Member lookup requires a unique successful resolution");
            }
            return response.bodyTo(Resolution.class);
        });
    }
}
