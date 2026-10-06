package io.github.brainboxemb.eventtiming.timingpoint.io.devices.power;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;

/**
 * Deterministic power device used with {@link SimulatedAntenna}.
 */
public final class SimulatedPowerDevice implements PowerDevice {
    private final SimulatedAntenna antenna;

    public SimulatedPowerDevice(
            SimulatedAntenna antenna) {
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        this.antenna = antenna;
        antenna.attachExternalPowerDevice();
    }

    @Override
    public void powerOn() {
        antenna.setExternallyPowered(
                true);
    }

    @Override
    public void powerOff() {
        antenna.setExternallyPowered(
                false);
    }

    public boolean powered() {
        return antenna.powered();
    }
}
