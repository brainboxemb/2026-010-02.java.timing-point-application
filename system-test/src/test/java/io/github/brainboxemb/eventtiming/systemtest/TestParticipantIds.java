package io.github.brainboxemb.eventtiming.systemtest;

import java.util.Locale;

/**
 * Canonical participant identifiers for black-box test data.
 *
 * <p>Normal: TT-A-0001-1 and TT-A-0001-2 belong to RT-A-0001,
 * with TeamId 0001. The -1/-2 suffix distinguishes physical tags,
 * not participants. Reserve identifiers use R instead of A; reserve
 * RegistrationId-to-TeamId assignment comes from reference data rather
 * than from parsing an identifier.</p>
 *
 * <p>IF-03 direct-registration tests send only RegistrationId. They do
 * not claim to verify RFID tag-to-registration mapping.</p>
 */
final class TestParticipantIds {
    private TestParticipantIds() {
    }

    static String normalRegistration(int teamNumber) {
        return "RT-A-" + number(teamNumber);
    }

    static String normalTag(int teamNumber, int tagNumber) {
        return "TT-A-" + number(teamNumber) + "-" + tag(tagNumber);
    }

    static String normalTeam(int teamNumber) {
        return number(teamNumber);
    }

    static String reserveRegistration(int reserveNumber) {
        return "RT-R-" + number(reserveNumber);
    }

    static String reserveTag(int reserveNumber, int tagNumber) {
        return "TT-R-" + number(reserveNumber) + "-" + tag(tagNumber);
    }

    private static String number(int value) {
        if (value < 1 || value > 9999) {
            throw new IllegalArgumentException("Participant numbers must be 0001..9999");
        }
        return String.format(Locale.ROOT, "%04d", value);
    }

    private static int tag(int value) {
        if (value != 1 && value != 2) {
            throw new IllegalArgumentException("Physical tag number must be 1 or 2");
        }
        return value;
    }
}
