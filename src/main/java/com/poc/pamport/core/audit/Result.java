package com.poc.pamport.core.audit;

/**
 * Result mirrors the Go struct for audit payloads.
 */
public final class Result {
    public final Throwable error;
    public final long affectedRecords;
    public final String userMessage;

    private Result(Throwable error, long affectedRecords, String userMessage) {
        this.error = error;
        this.affectedRecords = affectedRecords;
        this.userMessage = userMessage;
    }

    public static Result ok(long affectedRecords) {
        return new Result(null, affectedRecords, null);
    }

    public static Result error(Throwable error) {
        return new Result(error, 0, error == null ? null : error.getMessage());
    }

    public Result withUserMessage(String userMessage) {
        return new Result(error, affectedRecords, userMessage);
    }
}
