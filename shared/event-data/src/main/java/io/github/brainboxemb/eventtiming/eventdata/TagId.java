package io.github.brainboxemb.eventtiming.eventdata;

/**
 * Semantic RFID tag identity after provider-specific decoding/decryption.
 *
 * <p>A TagId consists of the registration type, a four-digit number and an
 * A/B tag variant. The value is retained as one packed integer.</p>
 */
public final class TagId {
    private static final int TYPE_SHIFT = 16;
    private static final int VARIANT_B_BIT = 0x8000;
    private static final int NUMBER_MASK = 0x7FFF;
    private static final int MAX_NUMBER = 9999;

    private final int packedValue;

    public TagId(String value) {
        if (value == null
                || value.length() != 6
                || !isType(value.charAt(0))
                || !isDigit(value.charAt(1))
                || !isDigit(value.charAt(2))
                || !isDigit(value.charAt(3))
                || !isDigit(value.charAt(4))
                || !isVariant(value.charAt(5))) {
            throw new IllegalArgumentException(
                    "TagId must be one character A-Z, four digits and variant A or B");
        }

        packedValue =
                pack(
                        value.charAt(0),
                        (value.charAt(1) - '0') * 1000
                                + (value.charAt(2) - '0') * 100
                                + (value.charAt(3) - '0') * 10
                                + value.charAt(4) - '0',
                        value.charAt(5));
    }

    public TagId(
            char type,
            int number,
            char variant) {
        packedValue = pack(type, number, variant);
    }

    private TagId(int packedValue) {
        char type = unpackType(packedValue);
        int number = unpackNumber(packedValue);
        char variant = unpackVariant(packedValue);
        if (!isType(type)
                || number < 0
                || number > MAX_NUMBER
                || !isVariant(variant)) {
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

    public char variant() {
        return unpackVariant(packedValue);
    }

    public String value() {
        int number = number();
        char[] value = new char[6];
        value[0] = type();
        value[1] = (char) ('0' + number / 1000);
        value[2] = (char) ('0' + number / 100 % 10);
        value[3] = (char) ('0' + number / 10 % 10);
        value[4] = (char) ('0' + number % 10);
        value[5] = variant();
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
        if (!isVariant(variant)) {
            throw new IllegalArgumentException(
                    "TagId variant must be A or B");
        }

        return type << TYPE_SHIFT
                | (variant == 'B' ? VARIANT_B_BIT : 0)
                | number;
    }

    private static char unpackType(int packedValue) {
        return (char) (packedValue >>> TYPE_SHIFT);
    }

    private static int unpackNumber(int packedValue) {
        return packedValue & NUMBER_MASK;
    }

    private static char unpackVariant(int packedValue) {
        return (packedValue & VARIANT_B_BIT) == 0
                ? 'A'
                : 'B';
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
