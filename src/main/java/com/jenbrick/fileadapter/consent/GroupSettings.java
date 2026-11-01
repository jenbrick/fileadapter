package com.jenbrick.fileadapter.consent;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configure group lookup separately from member lookup. Both internal group number and customer ID
 * are required.
 */
public record GroupSettings(String version,@DefaultValue("EXTERNAL_GROUP_ID") String sourceField,Lookup lookup) {
    // Both values come from group lookup and are passed to the member-demographics request.
    public record Context(String internalGroupNumber,String customerId) {}
    public record Lookup(ConsentSettings.LookupMode mode,URI endpoint,Duration connectTimeout,
                         Duration readTimeout,List<Fixture> fixtures) {}
    public record Fixture(String externalGroupId,Context result) {}
    public void validate() {
        if(version==null || version.isBlank() || sourceField==null || sourceField.isBlank() || lookup==null || lookup.mode()==null)
            throw new IllegalArgumentException("Group lookup requires version, source-field and mode");
        com.jenbrick.fileadapter.mapping.JsonPaths.check(sourceField);
        if(lookup.mode()==ConsentSettings.LookupMode.HTTP) {
            var u=lookup.endpoint();
            if(u==null || !List.of("http","https").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null
                    || u.getFragment()!=null || u.getQuery()!=null || lookup.connectTimeout()==null
                    || lookup.connectTimeout().isNegative() || lookup.connectTimeout().isZero()
                    || lookup.readTimeout()==null || lookup.readTimeout().isNegative() || lookup.readTimeout().isZero())
                throw new IllegalArgumentException("Invalid group lookup endpoint/timeouts");
        } else {
            if(lookup.fixtures()==null || lookup.fixtures().isEmpty())throw new IllegalArgumentException("Group fixtures required");
            // A fixture must resolve one external group ID to one complete context.
            var keys=new java.util.HashSet<String>();
            for(var fixture:lookup.fixtures()) {
                if(fixture.externalGroupId()==null || fixture.externalGroupId().isBlank() || !keys.add(fixture.externalGroupId()))
                    throw new IllegalArgumentException("Group fixtures must have unique external group IDs");
                check(fixture.result());
            }
        }
    }
    // Never let a partial group response reach the member service.
    public static Context check(Context result) {
        if(result==null || result.internalGroupNumber()==null || result.internalGroupNumber().isBlank()
                || result.customerId()==null || result.customerId().isBlank())throw new IllegalArgumentException("Incomplete group context");
        return result;
    }
}
