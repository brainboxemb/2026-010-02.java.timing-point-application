package io.github.brainboxemb.eventtiming.timingpoint.io.devices.power;

/**
 * Simple controllable power device.
 *
 * <p>The device represents an external power channel such as a relay or GPIO
 * output. It is independent from the protocol of the device being powered.</p>
 */
public interface PowerDevice {
    void powerOn();

    void powerOff();
}
