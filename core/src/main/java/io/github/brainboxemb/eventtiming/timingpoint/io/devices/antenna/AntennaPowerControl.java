package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/**
 * Optional installation-owned external power control for one antenna.
 *
 * <p>The power mechanism may be a relay, GPIO-controlled supply or another
 * installation device unrelated to the antenna vendor protocol.</p>
 */
public interface AntennaPowerControl {
    void powerOn();

    void powerOff();
}
