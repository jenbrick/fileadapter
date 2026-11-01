package com.jenbrick.fileadapter.consent;

import com.jenbrick.fileadapter.consent.ConsentSettings.Resolution;

/**
 * This is where the member-demographics service plugs in. Its request includes the group context we
 * already resolved.
 */
public interface MemberResolver {
    Resolution resolve(MemberLookupPayload member, ConsentSettings settings);
}
