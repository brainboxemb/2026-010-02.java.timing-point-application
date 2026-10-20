package io.github.brainboxemb.eventtiming.eventdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TagIdTest {

    @Test
    public void storesBVariantInExplicitTwoBitState() {
        TagId tagId = new TagId("N0042B");

        assertEquals('N' << 16 | 0x8000 | 42, tagId.packedValue());
        assertEquals('N', tagId.type());
        assertEquals(42, tagId.number());
        assertTrue(tagId.hasVariant());
        assertEquals('B', tagId.variant());
        assertEquals("N0042B", tagId.value());
        assertEquals(
                tagId,
                TagId.fromPackedValue(
                        tagId.packedValue()));
    }

    @Test
    public void aVariantUsesExplicitTwoBitState() {
        TagId tagId = new TagId("N0042A");

        assertEquals('N' << 16 | 42, tagId.packedValue());
        assertTrue(tagId.hasVariant());
        assertEquals('A', tagId.variant());
        assertEquals("N0042A", tagId.value());
        assertNotEquals(tagId, new TagId("N0042B"));
    }

    @Test
    public void tagWithoutVariantUsesThirdTwoBitState() {
        TagId tagId = new TagId("N0042");

        assertEquals('N' << 16 | 0x4000 | 42, tagId.packedValue());
        assertEquals('N', tagId.type());
        assertEquals(42, tagId.number());
        assertFalse(tagId.hasVariant());
        assertEquals("N0042", tagId.value());
        assertEquals(
                tagId,
                new TagId('N', 42));
        assertEquals(
                tagId,
                TagId.fromPackedValue(
                        tagId.packedValue()));
        assertNotEquals(tagId, new TagId("N0042A"));
        assertNotEquals(tagId, new TagId("N0042B"));
    }

    @Test(expected = IllegalStateException.class)
    public void variantIsUnavailableWhenTagHasNoVariant() {
        new TagId("N0042").variant();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedVariant() {
        new TagId("N0042C");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortNumber() {
        new TagId("N042");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPackedNumberOutsideContract() {
        TagId.fromPackedValue(
                'N' << 16 | 10_000);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReservedVariantBitPattern() {
        TagId.fromPackedValue(
                'N' << 16 | 0xC000 | 42);
    }
}
