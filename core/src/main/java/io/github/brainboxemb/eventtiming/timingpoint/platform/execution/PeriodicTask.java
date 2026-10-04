package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

/**
 * Handle for one registered periodic operation.
 *
 * <p>Closing the handle cancels only this periodic registration. It does not
 * close the executor that may be shared with other registrations.</p>
 */
public interface PeriodicTask extends AutoCloseable {
    @Override
    void close();
}
