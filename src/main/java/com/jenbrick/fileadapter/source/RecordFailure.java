package com.jenbrick.fileadapter.source;

/**
 * Use this exception for safe error details: field names and record numbers, never raw member values.
 */
public final class RecordFailure extends IllegalArgumentException {
    public RecordFailure(String safeDetail) { super(safeDetail); }
}
