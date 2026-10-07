package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

/**
 * Runtime factory for application timing-time sources.
 *
 * <p>{@link PlatformEnvironment} owns the raw JVM/OS time facilities.
 * RuntimeTimeSources composes those facilities into the semantic
 * {@link TimeSource} instances used by timing Domain and I/O components.</p>
 *
 * <p>The sharing scope is deliberately chosen by the composition root. One
 * call creates one timing source; Runtime may pass that same instance to every
 * component that must use the same timing basis. A future multi-system
 * composition may create more than one source without coupling TimeSource
 * itself to TimingSystem or TimingNode.</p>
 */
final class RuntimeTimeSources {
    private final PlatformEnvironment platform;

    RuntimeTimeSources(
            PlatformEnvironment platform) {
        if (platform == null) {
            throw new IllegalArgumentException(
                    "platform must not be null");
        }
        this.platform = platform;
    }

    /**
     * Creates one timing source from the current raw platform wall-clock basis.
     *
     * <p>The baseline source applies no correction. A later synchronization
     * design can select a corrected implementation here while consumers keep
     * depending only on TimeSource.</p>
     */
    TimeSource createTimeSource() {
        return new ClockTimeSource(
                platform.clock());
    }
}
