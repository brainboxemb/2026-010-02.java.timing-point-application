package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/** Deterministic external power channel for SimulatedAntenna verification. */
public final class SimulatedAntennaPowerControl implements AntennaPowerControl {
    private final SimulatedAntenna antenna;

    public SimulatedAntennaPowerControl(SimulatedAntenna antenna) {
        if (antenna == null) {
            throw new IllegalArgumentException("antenna must not be null");
        }
        this.antenna = antenna;
        antenna.attachExternalPowerControl();
    }

    @Override
    public void powerOn() {
        antenna.setExternallyPowered(true);
    }

    @Override
    public void powerOff() {
        antenna.setExternallyPowered(false);
    }

    public boolean powered() {
        return antenna.powered();
    }
}
