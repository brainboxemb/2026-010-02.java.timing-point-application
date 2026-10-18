package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

/**
 * Public value/result types used by the TimingNode component boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * lifecycle or architecture responsibility of its own.</p>
 */
public final class TimingNodeTypes {
    /** Prevents instantiation of this source-code grouping class. */
    private TimingNodeTypes() {
    }

    /** Functional OPEN/CLOSED state of a TimingNode. */
    public enum FunctionalState {
        /** No timing location is currently open for participant registration. */
        CLOSED,

        /** A timing location is open for participant registration. */
        OPEN
    }

    /**
     * Runtime readiness of the TimingNode itself, independent from OPEN/CLOSED
     * and from child-component health such as LogBook recovery state.
     */
    public enum OperationalState {
        /** Startup/recovery is still in progress. */
        INIT,

        /** The TimingNode's own startup/execution path is operational. */
        OK,

        /** The TimingNode's own startup/execution path failed. */
        ERROR
    }

    /** Result of the idempotent TimingNode open operation. */
    public enum OpenResult {
        /** The operation changed the TimingNode to OPEN. */
        OPENED,

        /** The TimingNode was already open; no second transition occurred. */
        ALREADY_OPEN
    }

    /** Result of the idempotent TimingNode close operation. */
    public enum CloseResult {
        /** The operation changed the TimingNode to CLOSED. */
        CLOSED,

        /** The TimingNode was already closed; no second transition occurred. */
        ALREADY_CLOSED
    }
}
