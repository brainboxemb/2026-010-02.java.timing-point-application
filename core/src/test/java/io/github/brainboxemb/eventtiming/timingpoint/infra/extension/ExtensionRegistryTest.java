package io.github.brainboxemb.eventtiming.timingpoint.infra.extension;

import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.simulation.SimulationEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExtensionRegistryTest {
    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void builtInsExposeReferenceProviders() {
        ExtensionRegistry registry =
                ExtensionRegistry.builtIns();

        assertEquals(
                DefaultEventDataProvider.class,
                registry.eventDataProvider("reference")
                        .getClass());
        assertEquals(
                DefaultTimingDataProvider.class,
                registry.timingDataProvider("reference")
                        .getClass());
        assertEquals(
                SimulationEventDataProvider.class,
                registry.eventDataProvider("simulation")
                        .getClass());
    }

    @Test
    public void rejectsUnknownProviderIds() {
        ExtensionRegistry registry =
                ExtensionRegistry.builtIns();

        try {
            registry.eventDataProvider("missing-event");
            fail("expected unknown EventData provider");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("Unknown EventDataProvider id"));
        }

        try {
            registry.timingDataProvider("missing-timing");
            fail("expected unknown TimingData provider");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("Unknown TimingDataProvider id"));
        }
    }

    @Test
    public void discoversTypedProvidersFromExternalJar()
            throws Exception {
        Path jar =
                externalProviderJar(
                        "external-event",
                        "external-timing");

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {
                                jar.toUri().toURL()
                        },
                        ExtensionRegistryTest.class
                                .getClassLoader())) {
            ExtensionRegistry registry =
                    ExtensionRegistry.discover(
                            loader);

            EventDataProvider eventDataProvider =
                    registry.eventDataProvider(
                            "external-event");
            TimingDataProvider timingDataProvider =
                    registry.timingDataProvider(
                            "external-timing");

            assertEquals(
                    "external.ExternalEventDataProvider",
                    eventDataProvider.getClass()
                            .getName());
            assertEquals(
                    "external.ExternalTimingDataProvider",
                    timingDataProvider.getClass()
                            .getName());
            assertTrue(
                    eventDataProvider.createEventData()
                            .isEmpty());
            assertTrue(
                    timingDataProvider.createFactory()
                            != null);
            assertTrue(
                    timingDataProvider.createCodec()
                            != null);
        }
    }

    @Test
    public void rejectsExternalProviderThatDuplicatesBuiltInId()
            throws Exception {
        Path jar =
                externalProviderJar(
                        "reference",
                        "external-timing");

        try (URLClassLoader loader =
                new URLClassLoader(
                        new URL[] {
                                jar.toUri().toURL()
                        },
                        ExtensionRegistryTest.class
                                .getClassLoader())) {
            try {
                ExtensionRegistry.discover(
                        loader);
                fail("expected duplicate EventData provider id");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains(
                                        "Duplicate EventDataProvider id reference"));
            }
        }
    }

    /**
     * Builds a real service-provider JAR during the test.
     *
     * <p>This is the V04 proof that discovery works through the Java 8
     * ClassLoader/ServiceLoader boundary rather than only through test doubles
     * or classes already linked directly by Runtime.</p>
     */
    private Path externalProviderJar(
            String eventProviderId,
            String timingProviderId)
            throws Exception {
        Path sourceRoot =
                temporaryFolder
                        .newFolder("provider-src-"
                                + sanitize(eventProviderId)
                                + "-"
                                + sanitize(timingProviderId))
                        .toPath();
        Path classesRoot =
                temporaryFolder
                        .newFolder("provider-classes-"
                                + sanitize(eventProviderId)
                                + "-"
                                + sanitize(timingProviderId))
                        .toPath();

        Path packageDir =
                sourceRoot.resolve("external");
        Files.createDirectories(
                packageDir);

        Path eventSource =
                packageDir.resolve(
                        "ExternalEventDataProvider.java");
        Path timingSource =
                packageDir.resolve(
                        "ExternalTimingDataProvider.java");

        Files.write(
                eventSource,
                eventProviderSource(
                        eventProviderId)
                        .getBytes(
                                StandardCharsets.UTF_8));
        Files.write(
                timingSource,
                timingProviderSource(
                        timingProviderId)
                        .getBytes(
                                StandardCharsets.UTF_8));

        JavaCompiler compiler =
                ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "JDK compiler is required for extension discovery verification");
        }

        int result =
                compiler.run(
                        null,
                        null,
                        null,
                        "-source",
                        "8",
                        "-target",
                        "8",
                        "-classpath",
                        testClasspath(),
                        "-d",
                        classesRoot.toString(),
                        eventSource.toString(),
                        timingSource.toString());
        assertEquals(
                "external provider sources must compile",
                0,
                result);

        writeServiceFile(
                classesRoot,
                EventDataProvider.class.getName(),
                "external.ExternalEventDataProvider");
        writeServiceFile(
                classesRoot,
                TimingDataProvider.class.getName(),
                "external.ExternalTimingDataProvider");

        Path jar =
                temporaryFolder
                        .getRoot()
                        .toPath()
                        .resolve(
                                "external-providers-"
                                        + sanitize(eventProviderId)
                                        + "-"
                                        + sanitize(timingProviderId)
                                        + ".jar");

        try (OutputStream output =
                Files.newOutputStream(
                        jar);
                JarOutputStream jarOutput =
                        new JarOutputStream(
                                output);
                Stream<Path> stream =
                        Files.walk(
                                classesRoot)) {
            List<Path> files =
                    stream.filter(
                            Files::isRegularFile)
                            .collect(
                                    Collectors.toList());

            for (Path file : files) {
                String entryName =
                        classesRoot
                                .relativize(
                                        file)
                                .toString()
                                .replace(
                                        java.io.File.separatorChar,
                                        '/');
                jarOutput.putNextEntry(
                        new JarEntry(
                                entryName));
                Files.copy(
                        file,
                        jarOutput);
                jarOutput.closeEntry();
            }
        }

        return jar;
    }

    private static void writeServiceFile(
            Path classesRoot,
            String serviceName,
            String implementation)
            throws IOException {
        Path service =
                classesRoot
                        .resolve("META-INF")
                        .resolve("services")
                        .resolve(serviceName);
        Files.createDirectories(
                service.getParent());
        Files.write(
                service,
                (implementation + "\n")
                        .getBytes(
                                StandardCharsets.UTF_8));
    }

    private static String testClasspath() {
        String surefireClasspath =
                System.getProperty(
                        "surefire.test.class.path");
        if (surefireClasspath != null
                && !surefireClasspath.trim().isEmpty()) {
            return surefireClasspath;
        }
        return System.getProperty(
                "java.class.path");
    }

    private static String eventProviderSource(
            String id) {
        return "package external;\n"
                + "import io.github.brainboxemb.eventtiming.eventdata.EventData;\n"
                + "import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;\n"
                + "public final class ExternalEventDataProvider implements EventDataProvider {\n"
                + "  public String id() { return \""
                + javaString(id)
                + "\"; }\n"
                + "  public EventData createEventData() { return EventData.empty(); }\n"
                + "}\n";
    }

    private static String timingProviderSource(
            String id) {
        return "package external;\n"
                + "import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;\n"
                + "import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;\n"
                + "import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;\n"
                + "import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;\n"
                + "import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;\n"
                + "public final class ExternalTimingDataProvider implements TimingDataProvider {\n"
                + "  public String id() { return \""
                + javaString(id)
                + "\"; }\n"
                + "  public TimingDataFactory createFactory() { return new DefaultTimingDataFactory(); }\n"
                + "  public TimingDataCodec createCodec() { return new DefaultTimingDataCodec(); }\n"
                + "}\n";
    }

    private static String javaString(
            String value) {
        return value
                .replace(
                        "\\",
                        "\\\\")
                .replace(
                        "\"",
                        "\\\"");
    }

    private static String sanitize(
            String value) {
        return value.replaceAll(
                "[^A-Za-z0-9_-]",
                "_");
    }
}
