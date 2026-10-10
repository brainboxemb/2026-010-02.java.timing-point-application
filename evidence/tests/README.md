# Unit test report

Generated from the Maven Surefire XML produced by the canonical `mvn verify` run. Tests are **not** rerun to create this report.

## Summary

**Status: PASS**

| Tests | Failures | Errors | Skipped | Time (s) |
| ---: | ---: | ---: | ---: | ---: |
| 413 | 0 | 0 | 0 | 9.943 |

## Maven modules

| Module | Tests | Failures | Errors | Skipped | Time (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app` | 21 | 0 | 0 | 0 | 0.214 |
| `core` | 328 | 0 | 0 | 0 | 9.582 |
| `shared/event-data` | 4 | 0 | 0 | 0 | 0.037 |
| `shared/timing-data` | 60 | 0 | 0 | 0 | 0.110 |

## Test suites

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) | Raw XML |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest` | 2 | 0 | 0 | 0 | 0.041 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.MainTest` | 8 | 0 | 0 | 0 | 0.166 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.MainTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest` | 10 | 0 | 0 | 0 | 0.004 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest` | 1 | 0 | 0 | 0 | 0.003 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest` | 2 | 0 | 0 | 0 | 0.035 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest` | 5 | 0 | 0 | 0 | 0.112 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest` | 11 | 0 | 0 | 0 | 0.082 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest` | 2 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBookTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBookTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest` | 17 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest` | 20 | 0 | 0 | 0 | 0.060 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest` | 5 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest` | 8 | 0 | 0 | 0 | 0.014 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductorTest` | 9 | 0 | 0 | 0 | 0.014 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistenceTest` | 8 | 0 | 0 | 0 | 0.009 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistenceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest` | 4 | 0 | 0 | 0 | 0.289 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest` | 3 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest` | 2 | 0 | 0 | 0 | 0.077 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest` | 7 | 0 | 0 | 0 | 0.044 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest` | 1 | 0 | 0 | 0 | 0.026 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.property.DerivedPropertyTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.property.DerivedPropertyTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.property.SourcePropertyTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.property.SourcePropertyTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest` | 6 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest` | 10 | 0 | 0 | 0 | 1.086 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest` | 6 | 0 | 0 | 0 | 0.335 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest` | 5 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest` | 7 | 0 | 0 | 0 | 0.031 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest` | 6 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskControllerTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskControllerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunnerTest` | 4 | 0 | 0 | 0 | 0.253 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunnerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutorTest` | 11 | 0 | 0 | 0 | 0.069 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutorTest` | 9 | 0 | 0 | 0 | 0.036 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunnerTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunnerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest` | 9 | 0 | 0 | 0 | 1.636 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest` | 8 | 0 | 0 | 0 | 0.018 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest` | 5 | 0 | 0 | 0 | 1.208 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest` | 3 | 0 | 0 | 0 | 0.008 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest` | 4 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest` | 3 | 0 | 0 | 0 | 0.014 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest` | 5 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest` | 1 | 0 | 0 | 0 | 0.640 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest` | 2 | 0 | 0 | 0 | 0.669 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest` | 16 | 0 | 0 | 0 | 1.464 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest` | 3 | 0 | 0 | 0 | 0.014 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest` | 9 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest` | 50 | 0 | 0 | 0 | 0.252 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest` | 1 | 0 | 0 | 0 | 0.119 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest` | 4 | 0 | 0 | 0 | 0.930 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataTest` | 2 | 0 | 0 | 0 | 0.037 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.LocationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.LocationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.NodeIdTest` | 10 | 0 | 0 | 0 | 0.038 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.NodeIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest` | 8 | 0 | 0 | 0 | 0.020 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest` | 2 | 0 | 0 | 0 | 0.015 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest` | 19 | 0 | 0 | 0 | 0.035 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest.xml) |

## Failures and errors

None.

## Raw evidence

The original Surefire XML/TXT files are retained below this directory for detailed inspection.

## Separate-process system verification

The canonical Maven summary above covers the ordinary module tests. The separately executed process-level VC-ST1 verification is retained under [system-test/](system-test/README.md), including the real per-node LogBook persistence file produced by VC-ST1-002.
