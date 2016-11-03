package com.nike.riposte.server.handler.base;

/**
 * Enum that can be used to indicate whether you want the pipeline to continue propagating a given event, or whether you
 * want that event to stop.
 *
 * @author Nic Munroe
 */
public enum PipelineContinuationBehavior {
    /**
     * Use this when you want an event to continue being propagated to handlers further down the pipeline.
     */
    CONTINUE,
    /**
     * Use this when you want an event to stop being propagated.
     */
    DO_NOT_FIRE_CONTINUE_EVENT;
}
