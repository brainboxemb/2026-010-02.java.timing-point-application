package oss.relay.eventtiming.timingpoint.domain.node.logbook;

/**
 * Engineering/test control for one LogBook write path.
 *
 * <p>This boundary is deliberately separate from {@link LogBookAccessor}; normal
 * application code must not depend on fault injection.</p>
 */
public interface LogBookTestControl {

    /** One-shot fault applied to the next LogBook command write. */
    enum WriteFailure {
        FAIL,
        TIMEOUT
    }

    /**
     * Arms one failure for the next LogBook command write.
     *
     * @throws IllegalStateException when a test failure is already armed
     */
    void armNextWriteFailure(WriteFailure failure);
}
