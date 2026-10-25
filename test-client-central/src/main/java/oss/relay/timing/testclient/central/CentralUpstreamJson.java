package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodec;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.UpstreamDataCodec;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.nio.charset.StandardCharsets;

/** Canonical IF-06 JSON used only for Central diagnostic/raw-message presentation. */
final class CentralUpstreamJson {
    private static final UpstreamDataCodec CODEC =
            new DefaultUpstreamDataCodec(
                    new DefaultTimingDataCodec());

    private CentralUpstreamJson() {
    }

    static String encode(UpstreamData data) {
        try {
            return new String(
                    CODEC.encode(data),
                    StandardCharsets.UTF_8);
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode Central IF-06 message",
                    ex);
        }
    }
}
