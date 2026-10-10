package io.github.brainboxemb.eventtiming.testclient;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/** Resource integrity, especially Maven binary filtering and ICO packaging. */
class EngineeringIconAssetsTest {

    @Test
    void javafxStageIconIsReadablePng() throws Exception {
        try (InputStream input = getClass()
                .getResourceAsStream("/icons/event-timing.png")) {
            assertNotNull(input, "Missing JavaFX Stage icon");
            var png = ImageIO.read(input);
            assertNotNull(png, "Corrupted PNG resource");
            assertEquals(128, png.getWidth());
            assertEquals(128, png.getHeight());
        }
    }

    @Test
    void windowsLauncherIconContainsMultipleValidPngResolutions() throws Exception {
        byte[] ico;
        try (InputStream input = getClass()
                .getResourceAsStream("/icons/event-timing.ico")) {
            assertNotNull(input, "Missing Windows launcher icon");
            ico = input.readAllBytes();
        }
        ByteBuffer bytes = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, Short.toUnsignedInt(bytes.getShort()));
        assertEquals(1, Short.toUnsignedInt(bytes.getShort()));
        int entries = Short.toUnsignedInt(bytes.getShort());
        assertTrue(entries >= 4, "Expected multiple Windows taskbar resolutions");

        Set<Integer> dimensions = new HashSet<>();
        for (int i = 0; i < entries; i++) {
            int offset = 6 + i * 16;
            int width = Byte.toUnsignedInt(ico[offset]);
            int height = Byte.toUnsignedInt(ico[offset + 1]);
            if (width == 0) width = 256;
            if (height == 0) height = 256;
            assertEquals(width, height);
            int imageSize = bytes.getInt(offset + 8);
            int imageOffset = bytes.getInt(offset + 12);
            assertTrue(imageOffset >= 6 + entries * 16);
            assertTrue(imageSize > 0 && (long) imageOffset + imageSize <= ico.length);
            var decoded = ImageIO.read(new ByteArrayInputStream(
                    ico, imageOffset, imageSize));
            assertNotNull(decoded, "ICO entry " + i + " is not a valid PNG");
            assertEquals(width, decoded.getWidth());
            dimensions.add(width);
        }
        assertTrue(dimensions.contains(16));
        assertTrue(dimensions.contains(32));
        assertTrue(dimensions.contains(48));
        assertTrue(dimensions.contains(128));
    }
}
