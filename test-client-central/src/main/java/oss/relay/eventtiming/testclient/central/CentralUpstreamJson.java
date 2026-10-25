package oss.relay.eventtiming.testclient.central;

import oss.relay.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import oss.relay.eventtiming.upstreamdata.UpstreamData;
import oss.relay.eventtiming.upstreamdata.UpstreamDataCodec;
import oss.relay.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

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
