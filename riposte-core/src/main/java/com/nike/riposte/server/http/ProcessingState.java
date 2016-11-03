package com.nike.riposte.server.http;

/**
 * Interface describing what methods need to be available for a processing state object.
 *
 * @author Nic Munroe
 */
public interface ProcessingState {

    /**
     * Calling this will clean this object's state for a new request. Implementations should do anything necessary to
     * properly close/flush/clean/remove/delete/etc any state that might be stale leftover state from a previous
     * request.
     */
    void cleanStateForNewRequest();

}
