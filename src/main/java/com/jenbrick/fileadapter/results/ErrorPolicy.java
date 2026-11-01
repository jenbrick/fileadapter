package com.jenbrick.fileadapter.results;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Customer text is approved configuration, never an exception or arbitrary HTTP response text. */
public record ErrorPolicy(String contractKey, Severity enrichmentSeverity, Severity projectionSeverity,
        @DefaultValue("RESULT_CODE") String codeField, @DefaultValue("RESULT_REMARKS") String remarksField,
        @DefaultValue("16") int codeWidth, @DefaultValue("160") int remarksWidth,
        boolean includeWarnings, boolean includeSuccess, List<Rule> rules, Map<String,ServiceCode> serviceCodes,
        @DefaultValue("resolved.owner.contract") String resolvedContractKey,
        @DefaultValue("resolved.owner.contractMemberGuid") String resolvedMemberKey) {
    public enum Severity { HARD, MEDIUM, SOFT }
    public enum Scope { FILE, CONTRACT, MEMBER, RECORD }
    public enum Condition { MISSING, EQUALS }
    public record Rule(String source, Condition condition, String equalsValue, Severity severity,
                       String code, String customerRemarks, String internalRemarks) {}
    public record ServiceCode(String customerCode, String customerRemarks, String internalRemarks) {}
    public static ErrorPolicy defaults() {
        return new ErrorPolicy(null,Severity.HARD,Severity.HARD,"RESULT_CODE","RESULT_REMARKS",16,160,false,false,null,null,"resolved.owner.contract","resolved.owner.contractMemberGuid");
    }
    public void validate() {
        if(codeField==null || remarksField==null || codeField.equals(remarksField)
                || !codeField.matches("[A-Za-z_][A-Za-z0-9_-]*") || !remarksField.matches("[A-Za-z_][A-Za-z0-9_-]*")
                || codeWidth<16 || codeWidth>128 || remarksWidth<160 || remarksWidth>4096)
            throw new IllegalArgumentException("Invalid result field names or fixed-width sizes");
        com.jenbrick.fileadapter.mapping.JsonPaths.check(resolvedContractKey);
        com.jenbrick.fileadapter.mapping.JsonPaths.check(resolvedMemberKey);
        if(contractKey!=null) com.jenbrick.fileadapter.mapping.JsonPaths.check(contractKey);
        if(enrichmentSeverity==Severity.SOFT || projectionSeverity==Severity.SOFT)
            throw new IllegalArgumentException("A failed enrichment or projection cannot be warning-only");
        if((enrichmentSeverity==Severity.MEDIUM || projectionSeverity==Severity.MEDIUM) && contractKey==null)
            throw new IllegalArgumentException("Contract isolation requires contract-key");
        if(rules!=null) for(var rule:rules) {
            com.jenbrick.fileadapter.mapping.JsonPaths.check(rule.source());
            if(rule.condition()==null || rule.severity()==null || (rule.condition()==Condition.EQUALS && rule.equalsValue()==null))
                throw new IllegalArgumentException("Invalid result validation rule");
            if(rule.severity()==Severity.MEDIUM && contractKey==null) throw new IllegalArgumentException("MEDIUM requires contract-key");
            checkText(rule.code(),rule.customerRemarks());
        }
        if(serviceCodes!=null) serviceCodes.forEach((key,value)->{
            if(key==null || key.isBlank() || value==null) throw new IllegalArgumentException("Invalid service result code");
            checkText(value.customerCode(),value.customerRemarks());
        });
    }
    private void checkText(String code,String remarks) {
        if(code==null || !code.matches("[A-Z0-9_]+") || code.length()>codeWidth || remarks==null
                || remarks.codePointCount(0,remarks.length())>remarksWidth || remarks.contains("\n") || remarks.contains("\r"))
            throw new IllegalArgumentException("Invalid approved customer result text");
    }
}
