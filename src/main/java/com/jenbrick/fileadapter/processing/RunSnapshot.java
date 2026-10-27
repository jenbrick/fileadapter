package com.jenbrick.fileadapter.processing;

import com.jenbrick.fileadapter.config.IngestionProperties.Delivery;
import com.jenbrick.fileadapter.config.IngestionProperties.Mode;
import com.jenbrick.fileadapter.config.Profile;

/** Persisted before parsing. Bearer tokens are deliberately not part of this snapshot. */
public record RunSnapshot(int engineVersion, Profile profile, Mode mode, Delivery delivery) {}
