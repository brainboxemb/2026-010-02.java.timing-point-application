package oss.relay.timing.timingpoint.platform.events;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EventInputTest {

    @Test
    public void componentOwnsHowBoundSourceIsConsumed() {
        Receiver receiver = new Receiver();
        Event<String> source = new Event<String>();

        EventInput<Receiver, String> input =
                EventInput.of(
                        (component, eventSource) ->
                                eventSource.subscribe(component::receive));

        input.bind(receiver, source);
        source.emit("value");

        assertEquals("value", receiver.lastValue);
    }

    private static final class Receiver {
        private String lastValue;

        private void receive(String value) {
            lastValue = value;
        }
    }
}
