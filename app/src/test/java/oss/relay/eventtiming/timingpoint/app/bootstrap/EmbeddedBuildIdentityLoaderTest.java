package oss.relay.eventtiming.timingpoint.app.bootstrap;

import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.infra.EmbeddedBuildIdentityLoader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class EmbeddedBuildIdentityLoaderTest {
    @Test
    public void loadsFilteredBuildIdentityIntoApplication() {
        BuildIdentity identity = EmbeddedBuildIdentityLoader.load();
        String expectedProjectVersion = System.getProperty("eventTiming.expectedProjectVersion");

        assertNotNull("Maven must expose the project version to the test JVM", expectedProjectVersion);
        assertEquals("timing-application", identity.application());
        assertEquals(expectedProjectVersion, identity.version());
        assertEquals("1", identity.apiVersion());
        assertTrue(identity.revision().matches("[0-9a-f]{40}"));
        assertFalse(identity.sourceRef().contains("${"));
        assertFalse(identity.buildOrigin().contains("${"));
        assertTrue("local".equals(identity.buildOrigin())
                || "github-actions".equals(identity.buildOrigin()));
        assertTrue(identity.provenance().contains("revision=" + identity.revision()));
        assertTrue(identity.provenance().contains("sourceRef=" + identity.sourceRef()));
        assertTrue(identity.provenance().contains("buildOrigin=" + identity.buildOrigin()));
    }
}
