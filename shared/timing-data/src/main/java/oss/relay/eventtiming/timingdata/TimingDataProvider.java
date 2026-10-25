package oss.relay.eventtiming.timingdata;

/**
 * Provider SPI for one concrete IF-05 TimingRecord codec.
 *
 * <p>The record model itself is shared and fixed. Providers vary only the
 * external representation; they do not provide alternate Java object models.</p>
 */
public interface TimingDataProvider {

    String id();

    TimingDataCodec createCodec();
}
