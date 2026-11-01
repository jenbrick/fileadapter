package com.jenbrick.fileadapter.consent;

import com.jenbrick.fileadapter.consent.ConsentSettings.Resolution;

/** Implement this port to adapt the company's member demographics service. */
public interface MemberResolver {
    Resolution resolve(MemberLookupPayload member, ConsentSettings settings);
}
