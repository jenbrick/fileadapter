package com.jenbrick.fileadapter.consent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.consent.ConsentSettings.Resolution;

/** Implement this port to compose the company's member/contract/consent database or API calls. */
public interface MemberResolver {
    Resolution resolve(ObjectNode criteria, ConsentSettings settings);
}
