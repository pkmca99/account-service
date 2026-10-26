package com.example.accountservice.exception;

public class ConcurrentUpdateException extends RuntimeException {
    private final boolean preconditionFailed;

    public ConcurrentUpdateException(String message, boolean preconditionFailed) {
        super(message);
        this.preconditionFailed = preconditionFailed;
    }

    /** true when the client's If-Match version was stale (412); false for a lost race (409). */
    public boolean isPreconditionFailed() {
        return preconditionFailed;
    }
}
