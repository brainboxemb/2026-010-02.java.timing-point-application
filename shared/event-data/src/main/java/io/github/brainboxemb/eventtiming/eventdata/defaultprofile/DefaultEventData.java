package io.github.brainboxemb.eventtiming.eventdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Algorithmic default/reference EventData identity profile.
 *
 * <p>Two physical tags form one logical registration. Normal registrations map
 * directly to a TeamId; reserve registrations intentionally have no stable
 * TeamId mapping because that assignment is live race data.</p>
 */
public final class DefaultEventData extends EventData {
    public static final int FIRST_NUMBER = 1;
    public static final int LAST_NUMBER = 9999;

    private static final char NORMAL_TYPE = 'A';
    private static final char RESERVE_TYPE = 'R';

    private static final Pattern TAG_PATTERN =
            Pattern.compile(
                    "^TT-([AR])-(\\d{4})-([12])$");
    private static final Pattern TEAM_PATTERN =
            Pattern.compile(
                    "^(\\d{4})$");

    private final int firstNumber;
    private final int lastNumber;
    private final boolean reserveSupported;

    /** Creates the full default/reference identity range 0001..9999. */
    public DefaultEventData() {
        this(
                FIRST_NUMBER,
                LAST_NUMBER,
                true);
    }

    /**
     * Creates a deterministic range-limited profile.
     *
     * <p>Range-limited data sets are useful in deterministic fixtures.
     * They do not enable or implement antenna simulation.</p>
     */
    public DefaultEventData(
            int firstNumber,
            int lastNumber) {
        this(
                firstNumber,
                lastNumber,
                true);
    }

    /** Creates a normal-registration-only subset using the same identifier grammar. */
    public static DefaultEventData normalOnly(
            int firstNumber,
            int lastNumber) {
        return new DefaultEventData(
                firstNumber,
                lastNumber,
                false);
    }

    private DefaultEventData(
            int firstNumber,
            int lastNumber,
            boolean reserveSupported) {
        if (firstNumber < FIRST_NUMBER
                || lastNumber > LAST_NUMBER
                || firstNumber > lastNumber) {
            throw new IllegalArgumentException(
                    "default EventData range must satisfy 1 <= first <= last <= 9999");
        }
        this.firstNumber = firstNumber;
        this.lastNumber = lastNumber;
        this.reserveSupported = reserveSupported;
    }

    @Override
    public RegistrationId registrationIdFor(
            TagId tagId) {
        if (tagId == null) {
            throw new IllegalArgumentException(
                    "tagId must not be null");
        }

        Matcher matcher =
                TAG_PATTERN.matcher(
                        tagId.value());
        if (!matcher.matches()) {
            return null;
        }

        char type = matcher.group(1).charAt(0);
        int number = Integer.parseInt(matcher.group(2));
        if (!supported(type, number)) {
            return null;
        }

        return new RegistrationId(
                type,
                number);
    }

    @Override
    public List<TagId> tagIdsFor(
            RegistrationId registrationId) {
        if (!supported(registrationId)) {
            return Collections.emptyList();
        }

        String prefix =
                "TT-"
                        + registrationId.type()
                        + "-"
                        + fourDigits(registrationId.number())
                        + "-";
        return Collections.unmodifiableList(
                Arrays.asList(
                        new TagId(prefix + "1"),
                        new TagId(prefix + "2")));
    }

    @Override
    public TeamId teamIdFor(
            RegistrationId registrationId) {
        if (!supported(registrationId)
                || registrationId.type() != NORMAL_TYPE) {
            return null;
        }
        return new TeamId(
                fourDigits(
                        registrationId.number()));
    }

    @Override
    public RegistrationId registrationIdFor(
            TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }

        Matcher matcher =
                TEAM_PATTERN.matcher(
                        teamId.value());
        if (!matcher.matches()) {
            return null;
        }

        int number = Integer.parseInt(matcher.group(1));
        if (!inRange(number)) {
            return null;
        }

        return new RegistrationId(
                NORMAL_TYPE,
                number);
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    private boolean supported(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        return supported(
                registrationId.type(),
                registrationId.number());
    }

    private boolean supported(
            char type,
            int number) {
        return (type == NORMAL_TYPE
                        || reserveSupported
                                && type == RESERVE_TYPE)
                && inRange(number);
    }

    private boolean inRange(
            int number) {
        return number >= firstNumber
                && number <= lastNumber;
    }

    private static String fourDigits(
            int number) {
        char[] value = new char[4];
        value[0] = (char) ('0' + number / 1000);
        value[1] = (char) ('0' + number / 100 % 10);
        value[2] = (char) ('0' + number / 10 % 10);
        value[3] = (char) ('0' + number % 10);
        return new String(value);
    }
}
