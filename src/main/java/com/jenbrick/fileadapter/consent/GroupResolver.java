package com.jenbrick.fileadapter.consent;

/** Existing-group-service boundary: one external group ID in, internal group/customer IDs out. */
public interface GroupResolver {
    GroupSettings.Context resolve(String externalGroupId,GroupSettings settings);
}
