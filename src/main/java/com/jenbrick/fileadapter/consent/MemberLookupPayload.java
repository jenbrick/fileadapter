package com.jenbrick.fileadapter.consent;

/**
 * This is the complete member-service request. Keeping the fields explicit prevents payer or
 * consent data from slipping into it.
 */
public record MemberLookupPayload(String externalContractId,String relationshipCode,String firstName,
        String middleName,String lastName,String genderCode,String dateOfBirth,
        String internalGroupNumber,String customerId) {}
