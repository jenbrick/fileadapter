package com.jenbrick.fileadapter.consent;

/** Strict allowlist: demographics/member identifiers and resolved group context only. */
public record MemberLookupPayload(String externalContractId,String relationshipCode,String firstName,
        String middleName,String lastName,String genderCode,String dateOfBirth,
        String internalGroupNumber,String customerId) {}
