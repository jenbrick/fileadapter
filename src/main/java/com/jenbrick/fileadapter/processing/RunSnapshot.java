package com.jenbrick.fileadapter.processing;

import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;

/**
 * Save the selected profile and delivery settings with the run. Later YAML edits must not change a
 * request we are retrying.
 */
public record RunSnapshot(int engineVersion, Profile profile, Mode mode, Delivery delivery) {}
