package io.github.brainboxemb.eventtiming.eventdata;

/**
 * Semantic RFID tag identity after provider-specific decoding/decryption.
 *
 * <p>A TagId consists of one registration type and a four-digit number.
 * Some tag families additionally use an A/B physical-tag variant. The lower
 * sixteen packed bits contain the 14-bit number plus a two-bit variant code.</p>
 *
 * <p>The historical A/B encodings stay stable: 00 means A and 10 means B.
 * Code 01 represents a tag without a variant; 11 remains reserved.</p>
 */
public final class TagId {
    private static final int TYPE_SHIFT = 16;

    private static final int VARIANT_SHIFT = 14;
    private static final int VARIANT_MASK = 0x3 << VARIANT_SHIFT;
    private static final int VARIANT_A = 0x0 << VARIANT_SHIFT;
    private static final int VARIANT_NONE = 0x1 << VARIANT_SHIFT;
    private static final int VARIANT_B = 0x2 << VARIANT_SHIFT;

    private static final int NUMBER_MASK = 0x3FFF;
    private static final int MAX_NUMBER = 9999;

    private final int packedValue;

    public TagId(String value) {
        if (!validText(value)) {
            throw new IllegalArgumentException(
                    "TagId must be one character A-Z, four digits"
                            + " and optional variant A or B");
        }

        packedValue =
                pack(
                        value.charAt(0),
                        number(value),
                        value.length() == 6
                                ? value.charAt(5)
                                : '\0');
    }

    /** Creates a TagId without a physical-tag variant. */
    public TagId(
            char type,
            int number) {
        packedValue = pack(type, number, '\0');
    }

    /** Creates a TagId with physical-tag variant A or B. */
    public TagId(
            char type,
            int number,
            char variant) {
        packedValue = pack(type, number, variant);
    }

    private TagId(int packedValue) {
        char type = unpackType(packedValue);
        int number = unpackNumber(packedValue);
        int variantCode = packedValue & VARIANT_MASK;
        if (!isType(type)
                || number < 0
                || number > MAX_NUMBER
                || !isVariantCode(variantCode)) {
            throw new IllegalArgumentException("invalid packed TagId");
        }
        this.packedValue = packedValue;
    }

    public static TagId fromPackedValue(int packedValue) {
        return new TagId(packedValue);
    }

    public int packedValue() {
        return packedValue;
    }

    public char type() {
        return unpackType(packedValue);
    }

    public int number() {
        return unpackNumber(packedValue);
    }

    public boolean hasVariant() {
        return (packedValue & VARIANT_MASK) != VARIANT_NONE;
    }

    /**
     * Returns physical-tag variant A or B.
     *
     * @throws IllegalStateException when this TagId has no variant
     */
    public char variant() {
        int code = packedValue & VARIANT_MASK;
        if (code == VARIANT_A) {
            return 'A';
        }
        if (code == VARIANT_B) {
            return 'B';
        }
        throw new IllegalStateException("TagId has no variant");
    }

    public String value() {
        int number = number();
        char[] value = new char[hasVariant() ? 6 : 5];
        value[0] = type();
        value[1] = (char) ('0' + number / 1000);
        value[2] = (char) ('0' + number / 100 % 10);
        value[3] = (char) ('0' + number / 10 % 10);
        value[4] = (char) ('0' + number % 10);
        if (hasVariant()) {
            value[5] = variant();
        }
        return new String(value);
    }

    private static int pack(
            char type,
            int number,
            char variant) {
        if (!isType(type)) {
            throw new IllegalArgumentException(
                    "TagId type must be A-Z");
        }
        if (number < 0 || number > MAX_NUMBER) {
            throw new IllegalArgumentException(
                    "TagId number must be in range 0000..9999");
        }

        return type << TYPE_SHIFT
                | variantCode(variant)
                | number;
    }

    private static int variantCode(char variant) {
        if (variant == '\0') {
            return VARIANT_NONE;
        }
        if (variant == 'A') {
            return VARIANT_A;
        }
        if (variant == 'B') {
            return VARIANT_B;
        }
        throw new IllegalArgumentException(
                "TagId variant must be A, B or absent");
    }

    private static char unpackType(int packedValue) {
        return (char) (packedValue >>> TYPE_SHIFT);
    }

    private static int unpackNumber(int packedValue) {
        return packedValue & NUMBER_MASK;
    }

    private static boolean isVariantCode(int code) {
        return code == VARIANT_A
                || code == VARIANT_NONE
                || code == VARIANT_B;
    }

    private static boolean validText(String value) {
        return value != null
                && (value.length() == 5 || value.length() == 6)
                && isType(value.charAt(0))
                && isDigit(value.charAt(1))
                && isDigit(value.charAt(2))
                && isDigit(value.charAt(3))
                && isDigit(value.charAt(4))
                && (value.length() == 5 || isVariant(value.charAt(5)));
    }

    private static int number(String value) {
        return (value.charAt(1) - '0') * 1000
                + (value.charAt(2) - '0') * 100
                + (value.charAt(3) - '0') * 10
                + value.charAt(4) - '0';
    }

    private static boolean isType(char value) {
        return value >= 'A' && value <= 'Z';
    }

    private static boolean isVariant(char value) {
        return value == 'A' || value == 'B';
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TagId)) {
            return false;
        }
        TagId that = (TagId) other;
        return packedValue == that.packedValue;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(packedValue);
    }

    @Override
    public String toString() {
        return value();
    }
}
