package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.config.Profile;
import java.util.List;
import java.util.Map;

/** A small declarative JSON tree. No scripts, reflection or expression evaluation. */
public record Projection(String version, Scope scope, int maxRecords, List<String> enrichers,
                         List<Profile.ContractField> inputContract, String skipWhenEmpty, Map<String,Object> root, Batching batching) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Projection {}
    public enum Scope { RECORD, FILE, GROUPED }
    // Keep the original constructor for callers and saved pre-batching profiles.
    public Projection(String version, Scope scope, int maxRecords, List<String> enrichers,
                      List<Profile.ContractField> inputContract, String skipWhenEmpty, Map<String,Object> root) {
        this(version, scope, maxRecords, enrichers, inputContract, skipWhenEmpty, root, null);
    }
    /** Groups are indivisible; memberKey/maxMembersPerGroup are optional generic entity limits. */
    public record Batching(List<String> groupBy, int maxGroupsPerRequest, int maxRecordsPerGroup,
                           String memberKey, Integer maxMembersPerGroup, Where where) {}
    /** Exactly one of source, literalJson, object or array must be configured. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    public record Node(String source, String literalJson, String defaultValue, String defaultJson, boolean required,
                       Profile.BlankPolicy blankPolicy, List<String> transforms, Map<String,String> values,
                       Map<String,Node> object, List<Collection> array, List<Profile.ContractField> checks) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    public record Collection(Where where, List<String> groupBy, Node item) {}
    /** Configure source + present/equalsValue, or anyPresent. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    public record Where(String source, Boolean present, String equalsValue, List<String> anyPresent) {}
}
