package com.jenbrick.fileadapter.projection;

import com.jenbrick.fileadapter.config.Profile;
import java.util.List;
import java.util.Map;

/**
 * Describe the output JSON in configuration. Request boundaries, nested objects, and collections
 * are separate choices.
 */
public record Projection(String version, Scope scope, int maxRecords, List<String> enrichers,
                         List<Profile.ContractField> inputContract, String skipWhenEmpty, Map<String,Object> root, Batching batching) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public Projection {}
    // RECORD makes one request per row; FILE makes one per file; GROUPED packs complete groups into
    // bounded requests.
    public enum Scope { RECORD, FILE, GROUPED }
    // Existing in-code callers can omit batching when using FILE or RECORD scope.
    public Projection(String version, Scope scope, int maxRecords, List<String> enrichers,
                      List<Profile.ContractField> inputContract, String skipWhenEmpty, Map<String,Object> root) {
        this(version, scope, maxRecords, enrichers, inputContract, skipWhenEmpty, root, null);
    }
    /** Groups are indivisible; memberKey/maxMembersPerGroup are optional generic entity limits. */
    // The group limit controls request size. The row and member limits protect each indivisible
    // contract.
    public record Batching(List<String> groupBy, int maxGroupsPerRequest, int maxRecordsPerGroup,
                           String memberKey, Integer maxMembersPerGroup, Where where) {}
    /** Exactly one of source, literalJson, object or array must be configured. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    // Each node copies a value, supplies a literal, builds an object, or builds an array.
    public record Node(String source, String literalJson, String defaultValue, String defaultJson, boolean required,
                       Profile.BlankPolicy blankPolicy, List<String> transforms, Map<String,String> values,
                       Map<String,Node> object, List<Collection> array, List<Profile.ContractField> checks) {}
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    // Filtering chooses rows; grouping decides which rows describe the same item in this collection.
    public record Collection(Where where, List<String> groupBy, Node item) {}
    /** Configure source + present/equalsValue, or anyPresent. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(com.fasterxml.jackson.databind.PropertyNamingStrategies.KebabCaseStrategy.class)
    public record Where(String source, Boolean present, String equalsValue, List<String> anyPresent) {}
}
