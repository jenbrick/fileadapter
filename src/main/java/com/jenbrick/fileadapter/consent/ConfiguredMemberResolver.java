package com.jenbrick.fileadapter.consent;

import com.jenbrick.fileadapter.consent.ConsentSettings.Resolution;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Use demo fixtures or call the member-demographics adapter with the member-only payload.
 */
@Component
public class ConfiguredMemberResolver implements MemberResolver {
    private final RestClient.Builder builder;
    private final String token;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public ConfiguredMemberResolver(RestClient.Builder builder,
            @Value("${consent.lookup-bearer-token:}") String token, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.builder = builder;
        this.token = token;this.json=json;
    }
    @Override public Resolution resolve(MemberLookupPayload member, ConsentSettings settings) {
        var lookup = settings.lookup();
        // Match fixtures against the same member payload we would send over HTTP.
        com.fasterxml.jackson.databind.JsonNode criteria=json.valueToTree(member);
        if (lookup.mode() == ConsentSettings.LookupMode.FIXTURE) {
            var matches = lookup.fixtures().stream().filter(f -> f.match() != null && !f.match().isEmpty()
                    && f.match().entrySet().stream().allMatch(e ->
                        criteria.path(e.getKey()).asText().equals(e.getValue()))).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Lookup must resolve exactly one member");
            return matches.get(0).result();
        }
        // The company member API plugs in here; the file readers and JSON templates stay
        // independent of it.
        var http = HttpClient.newBuilder().connectTimeout(lookup.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(lookup.readTimeout());
        var client = builder.clone().requestFactory(factory).build();
        var request = client.post().uri(lookup.endpoint()).contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        if (!token.isBlank()) request.headers(h -> h.setBearerAuth(token));
        // The typed payload limits this call to member fields and resolved group context.
        return request.body(member).exchange((sent, response) -> {
            if (response.getStatusCode().value() != 200) {
                throw new IllegalArgumentException("Member lookup requires a unique successful resolution");
            }
            return response.bodyTo(Resolution.class);
        });
    }
}
