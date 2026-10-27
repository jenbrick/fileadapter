package com.jenbrick.fileadapter.mapping;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jenbrick.fileadapter.config.Profile;
import com.jenbrick.fileadapter.source.SourceRecord;

public interface RequestMapper {
    ObjectNode map(SourceRecord source, Profile profile);
}
