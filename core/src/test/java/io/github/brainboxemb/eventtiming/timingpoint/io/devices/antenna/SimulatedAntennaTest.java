package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.TagId;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SimulatedAntennaTest {
    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse(
                    "2026-10-01T12:00:00.000000000Z");

    @Test
    public void selfTestDoesNotStartInventoryAndObservationUsesEventSource() {
        SimulatedAntenna antenna = new SimulatedAntenna();
        AtomicReference<TagObservation> received =
                new AtomicReference<TagObservation>();
        antenna.tagObservedEvent().subscribe(received::set);

        AntennaInfo info = antenna.selfTest();
        assertEquals("simulated-antenna", info.identity());
        assertEquals("1", info.version());
        assertFalse(antenna.inventoryRunning());

        antenna.initialize();
        antenna.startInventory();
        assertTrue(antenna.inventoryRunning());

        TagObservation observation =
                new TagObservation(
                        new TagId("TAG-001"),
                        -42,
                        OBSERVED_AT);
        antenna.emit(observation);
        assertSame(observation, received.get());

        antenna.stopInventory();
        assertFalse(antenna.inventoryRunning());
        antenna.shutdown();
    }

    @Test
    public void externalPowerControlModelsPowerLossAndReinitialization() {
        SimulatedAntenna antenna = new SimulatedAntenna();
        SimulatedPowerDevice power =
                new SimulatedPowerDevice(antenna);

        assertFalse(power.powered());

        power.powerOn();
        assertTrue(power.powered());
        antenna.selfTest();
        antenna.initialize();
        antenna.startInventory();
        assertTrue(antenna.inventoryRunning());

        power.powerOff();
        assertFalse(power.powered());
        assertFalse(antenna.inventoryRunning());

        power.powerOn();
        try {
            antenna.startInventory();
            fail("power cycling should require reinitialization");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains("initialized"));
        }

        antenna.initialize();
        antenna.startInventory();
        assertTrue(antenna.inventoryRunning());
        antenna.shutdown();
    }

    @Test
    public void configurableFailurePointSupportsHardwareIndependentFaultTests() {
        SimulatedAntenna antenna = new SimulatedAntenna();
        antenna.setFailurePoint(
                SimulatedAntenna.FailurePoint.SELF_TEST);

        try {
            antenna.selfTest();
            fail("expected simulated self-test failure");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains("SELF_TEST"));
        }

        antenna.clearFailure();
        antenna.selfTest();
        antenna.initialize();
        antenna.setFailurePoint(
                SimulatedAntenna.FailurePoint.START_INVENTORY);

        try {
            antenna.startInventory();
            fail("expected simulated inventory-start failure");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("START_INVENTORY"));
        } finally {
            antenna.shutdown();
        }
    }

    @Test
    public void inventoryCannotStartBeforeInitialize() {
        SimulatedAntenna antenna = new SimulatedAntenna();
        try {
            antenna.startInventory();
            fail("startInventory should require initialize");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage().contains("initialized"));
        } finally {
            antenna.shutdown();
        }
    }
}
