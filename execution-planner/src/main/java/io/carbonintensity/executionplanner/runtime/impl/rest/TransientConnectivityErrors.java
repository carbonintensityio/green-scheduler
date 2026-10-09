package io.carbonintensity.executionplanner.runtime.impl.rest;

import java.io.IOException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Classifies whether a failure from the CarbonIntensity REST API is a transient, connection-level problem
 * worth retrying (DNS/connect failures, timeouts, ...), as opposed to a genuine HTTP error response
 * (4xx/5xx, surfaced as {@link CarbonIntensityApiException}) which a direct retry cannot fix and must
 * never be retried - see CIIO-470.
 */
final class TransientConnectivityErrors {

    private TransientConnectivityErrors() {
    }

    /**
     * Unwraps the {@link CompletionException}/{@link ExecutionException} wrapper(s) that
     * {@link java.util.concurrent.CompletableFuture} adds around an exception raised by an earlier stage,
     * to get at the real cause.
     */
    static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * @param cause an already-{@link #unwrap(Throwable) unwrapped} cause
     * @return {@code true} if this is a connection-level problem (never a {@link CarbonIntensityApiException},
     *         which represents a real HTTP status code and is not an {@link IOException}) - this covers
     *         {@code ConnectException} and timeouts too, since both are {@code IOException} subtypes
     */
    static boolean isTransient(Throwable cause) {
        return cause instanceof UnresolvedAddressException || cause instanceof IOException;
    }
}
