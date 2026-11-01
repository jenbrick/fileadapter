package com.jenbrick.fileadapter.consent;

/**
 * This is where the existing group service plugs in: external group ID in, internal group number
 * and customer ID out.
 */
public interface GroupResolver {
    GroupSettings.Context resolve(String externalGroupId,GroupSettings settings);
}
