package com.jenbrick.fileadapter.source;

/** Only put record numbers/configuration field names here, never source values. */
public final class RecordFailure extends IllegalArgumentException {
    public RecordFailure(String safeDetail) { super(safeDetail); }
}
