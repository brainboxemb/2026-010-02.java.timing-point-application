package oss.relay.eventtiming.timingdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class RegistrationIdTest {

    @Test
    public void storesTypeAndFourDigitNumberAsOnePackedValue() {
        TimingDataTypes.RegistrationId registrationId =
                new TimingDataTypes.RegistrationId("A0042");

        assertEquals('A' << 16 | 42, registrationId.packedValue());
        assertEquals('A', registrationId.type());
        assertEquals(42, registrationId.number());
        assertEquals("A0042", registrationId.value());
        assertEquals(
                registrationId,
                TimingDataTypes.RegistrationId.fromPackedValue(
                        registrationId.packedValue()));
        assertEquals("A0042", registrationId.toString());
    }

    @Test
    public void packedIdentityDistinguishesTypeAndNumber() {
        TimingDataTypes.RegistrationId registrationId =
                new TimingDataTypes.RegistrationId("A0042");

        assertNotEquals(
                registrationId,
                new TimingDataTypes.RegistrationId("R0042"));
        assertNotEquals(
                registrationId,
                new TimingDataTypes.RegistrationId("A0043"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingType() {
        new TimingDataTypes.RegistrationId("0042");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortNumber() {
        new TimingDataTypes.RegistrationId("A042");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonNumericNumber() {
        new TimingDataTypes.RegistrationId("A00X2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingValue() {
        new TimingDataTypes.RegistrationId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPackedValueOutsideSupportedTypes() {
        TimingDataTypes.RegistrationId.fromPackedValue(
                '@' << 16 | 42);
    }
}
