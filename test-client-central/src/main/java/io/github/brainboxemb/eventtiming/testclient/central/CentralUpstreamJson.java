package io.github.brainboxemb.eventtiming.testclient.central;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.nio.charset.StandardCharsets;

/** Canonical IF-06 JSON used only for Central diagnostic/raw-message presentation. */
final class CentralUpstreamJson {
    private static final UpstreamDataCodec CODEC =
            new DefaultUpstreamDataCodec();

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
