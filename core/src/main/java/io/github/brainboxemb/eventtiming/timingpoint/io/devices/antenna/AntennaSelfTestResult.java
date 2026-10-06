package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/** Immutable completion fact for one asynchronous antenna self-test. */
public final class AntennaSelfTestResult {
    private final AntennaId antennaId;
    private final boolean passed;
    private final AntennaInfo info;
    private final Throwable failure;

    private AntennaSelfTestResult(
            AntennaId antennaId,
            boolean passed,
            AntennaInfo info,
            Throwable failure) {
        this.antennaId = antennaId;
        this.passed = passed;
        this.info = info;
        this.failure = failure;
    }

    public static AntennaSelfTestResult passed(
            AntennaId antennaId,
            AntennaInfo info) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }
        if (info == null) {
            throw new IllegalArgumentException(
                    "info must not be null");
        }
        return new AntennaSelfTestResult(
                antennaId,
                true,
                info,
                null);
    }

    public static AntennaSelfTestResult failed(
            AntennaId antennaId,
            Throwable failure) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }
        if (failure == null) {
            throw new IllegalArgumentException(
                    "failure must not be null");
        }
        return new AntennaSelfTestResult(
                antennaId,
                false,
                null,
                failure);
    }

    public AntennaId antennaId() {
        return antennaId;
    }

    public boolean passed() {
        return passed;
    }

    public AntennaInfo info() {
        return info;
    }

    public Throwable failure() {
        return failure;
    }
}
