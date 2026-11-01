package com.jenbrick.fileadapter.consent;

import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ConfiguredGroupResolver implements GroupResolver {
    private final RestClient.Builder builder;
    private final String token;
    public record Request(String externalGroupId) {}
    public ConfiguredGroupResolver(RestClient.Builder builder,@Value("${group.lookup-bearer-token:}") String token) {
        this.builder=builder;this.token=token;
    }
    @Override public GroupSettings.Context resolve(String externalGroupId,GroupSettings settings) {
        var lookup=settings.lookup();
        if(lookup.mode()==ConsentSettings.LookupMode.FIXTURE) {
            var matches=lookup.fixtures().stream().filter(f->externalGroupId.equals(f.externalGroupId())).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("Group lookup must return exactly one group");
            return GroupSettings.check(matches.get(0).result());
        }
        var http=HttpClient.newBuilder().connectTimeout(lookup.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(lookup.readTimeout());
        var client=builder.clone().requestFactory(factory).build();
        var request=client.post().uri(lookup.endpoint()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
        if(!token.isBlank())request.headers(h->h.setBearerAuth(token));
        return request.body(new Request(externalGroupId)).exchange((sent,response)->{
            if(response.getStatusCode().value()!=200)throw new IllegalArgumentException("Group lookup requires a unique successful resolution");
            return GroupSettings.check(response.bodyTo(GroupSettings.Context.class));
        });
    }
}
