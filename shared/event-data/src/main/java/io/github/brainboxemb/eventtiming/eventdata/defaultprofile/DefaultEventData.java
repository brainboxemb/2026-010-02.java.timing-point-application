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

    private static final Pattern TAG_PATTERN =
            Pattern.compile(
                    "^TT-([AR])-(\\d{4})-([12])$");
    private static final Pattern REGISTRATION_PATTERN =
            Pattern.compile(
                    "^RT-([AR])-(\\d{4})$");
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
     * <p>This is used by engineering simulation to keep its advertised fixture
     * range bounded while reusing the same identifier grammar.</p>
     */
    public DefaultEventData(
            int firstNumber,
            int lastNumber) {
        this(
                firstNumber,
                lastNumber,
                true);
    }

    /**
     * Creates a normal-registration-only subset using the same identifier grammar.
     */
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
        if (!matcher.matches()
                || !inRange(matcher.group(2))
                || (!reserveSupported
                        && "R".equals(matcher.group(1)))) {
            return null;
        }

        return new RegistrationId(
                "RT-"
                        + matcher.group(1)
                        + "-"
                        + matcher.group(2));
    }

    @Override
    public List<TagId> tagIdsFor(
            RegistrationId registrationId) {
        ParsedRegistration parsed =
                parseRegistration(
                        registrationId);
        if (parsed == null) {
            return Collections.emptyList();
        }

        String prefix =
                "TT-"
                        + parsed.kind
                        + "-"
                        + parsed.number
                        + "-";
        return Collections.unmodifiableList(
                Arrays.asList(
                        new TagId(prefix + "1"),
                        new TagId(prefix + "2")));
    }

    @Override
    public TeamId teamIdFor(
            RegistrationId registrationId) {
        ParsedRegistration parsed =
                parseRegistration(
                        registrationId);
        if (parsed == null
                || !"A".equals(parsed.kind)) {
            return null;
        }
        return new TeamId(
                parsed.number);
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
        if (!matcher.matches()
                || !inRange(matcher.group(1))) {
            return null;
        }

        return new RegistrationId(
                "RT-A-"
                        + matcher.group(1));
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    private ParsedRegistration parseRegistration(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }

        Matcher matcher =
                REGISTRATION_PATTERN.matcher(
                        registrationId.value());
        if (!matcher.matches()
                || !inRange(matcher.group(2))
                || (!reserveSupported
                        && "R".equals(matcher.group(1)))) {
            return null;
        }

        return new ParsedRegistration(
                matcher.group(1),
                matcher.group(2));
    }

    private boolean inRange(
            String numberText) {
        int number =
                Integer.parseInt(
                        numberText);
        return number >= firstNumber
                && number <= lastNumber;
    }

    private static final class ParsedRegistration {
        private final String kind;
        private final String number;

        private ParsedRegistration(
                String kind,
                String number) {
            this.kind = kind;
            this.number = number;
        }
    }
}
