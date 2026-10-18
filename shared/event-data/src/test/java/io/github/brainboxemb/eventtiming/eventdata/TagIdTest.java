package io.github.brainboxemb.eventtiming.eventdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TagIdTest {

    @Test
    public void storesTypeNumberAndVariantAsOnePackedValue() {
        TagId tagId = new TagId("N0042B");

        assertEquals('N' << 16 | 0x8000 | 42, tagId.packedValue());
        assertEquals('N', tagId.type());
        assertEquals(42, tagId.number());
        assertEquals('B', tagId.variant());
        assertEquals("N0042B", tagId.value());
        assertEquals(
                tagId,
                TagId.fromPackedValue(
                        tagId.packedValue()));
    }

    @Test
    public void aVariantSharesRegistrationBitsWithoutVariantFlag() {
        TagId tagId = new TagId("N0042A");

        assertEquals('N' << 16 | 42, tagId.packedValue());
        assertNotEquals(tagId, new TagId("N0042B"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingVariant() {
        new TagId("N0042");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedVariant() {
        new TagId("N0042C");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortNumber() {
        new TagId("N042A");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPackedNumberOutsideContract() {
        TagId.fromPackedValue(
                'N' << 16 | 10_000);
    }
}
