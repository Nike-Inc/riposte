package com.nike.riposte.server.error.exception;

/**
 * This will be thrown when a nonblocking endpoint's {@link java.util.concurrent.CompletableFuture} fails to complete
 * within the necessary amount of time.
 *
 * @author Nic Munroe
 */
public class NonblockingEndpointCompletableFutureTimedOut extends RuntimeException {

    public final long timeoutValueMillis;

    public NonblockingEndpointCompletableFutureTimedOut(long timeoutValueMillis) {
        super("The CompletableFuture was cancelled because it was taking too long. "
              + "completable_future_timeout_value_millis=" + timeoutValueMillis);
        this.timeoutValueMillis = timeoutValueMillis;
    }
}
