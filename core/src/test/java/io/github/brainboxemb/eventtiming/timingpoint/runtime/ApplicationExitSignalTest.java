package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ApplicationExitSignalTest {
    @Test
    public void firstRestartRequestIsNotOverwrittenByLaterStop() throws Exception {
        ApplicationExitSignal signal = new ApplicationExitSignal();

        signal.requestRestart();
        signal.requestStop();

        assertEquals(
                TimingApplicationRuntime.ExitRequest.RESTART,
                signal.awaitRequest());
    }

    @Test
    public void keepLoggingRestartIsDistinctAndWinsWhenRequestedFirst()
            throws Exception {
        ApplicationExitSignal signal = new ApplicationExitSignal();

        signal.requestRestartKeepLogging();
        signal.requestRestart();
        signal.requestStop();

        assertEquals(
                TimingApplicationRuntime.ExitRequest.RESTART_KEEP_LOGGING,
                signal.awaitRequest());
    }

    @Test
    public void firstStopRequestIsNotOverwrittenByLaterRestart() throws Exception {
        ApplicationExitSignal signal = new ApplicationExitSignal();

        signal.requestStop();
        signal.requestRestart();

        assertEquals(
                TimingApplicationRuntime.ExitRequest.STOP,
                signal.awaitRequest());
    }
}
