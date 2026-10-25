package oss.relay.eventtiming.testclient;

/**
 * Stable, source-driven representation of the IF-03 capability snapshot.
 * No inferred defaults: missing sync is unknown, not a disabled capability.
 */
final class CapabilityDisplay {
    private CapabilityDisplay() { }

    static String format(ApiClient.CapabilitiesResult result) {
        if (result == null) return "UNKNOWN — sync failed or in progress";
        StringBuilder display = new StringBuilder();
        for (ApiClient.CapabilityInfo entry : result.capabilities()) {
            if (display.length() > 0) display.append("\n");
            display.append(entry.id()).append("   ")
                    .append(entry.supported() ? "yes" : "no").append(" / ")
                    .append(entry.enabled() ? "yes" : "no");
        }
        return display.length() == 0 ? "No capabilities advertised" : display.toString();
    }
}
