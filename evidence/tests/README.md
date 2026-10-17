# Unit test report

Generated from the Maven Surefire XML produced by the canonical `mvn verify` run. Tests are **not** rerun to create this report.

## Summary

**Status: PASS**

| Tests | Failures | Errors | Skipped | Time (s) |
| ---: | ---: | ---: | ---: | ---: |
| 518 | 0 | 0 | 0 | 11.756 |

## Maven modules

| Module | Tests | Failures | Errors | Skipped | Time (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app` | 22 | 0 | 0 | 0 | 0.235 |
| `core` | 405 | 0 | 0 | 0 | 11.210 |
| `shared/event-data` | 4 | 0 | 0 | 0 | 0.046 |
| `shared/timing-data` | 65 | 0 | 0 | 0 | 0.131 |
| `shared/upstream-data` | 22 | 0 | 0 | 0 | 0.134 |

## Test suites

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) | Raw XML |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest` | 2 | 0 | 0 | 0 | 0.054 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.MainTest` | 8 | 0 | 0 | 0 | 0.169 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.MainTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest` | 10 | 0 | 0 | 0 | 0.008 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.StartupParametersTest` | 1 | 0 | 0 | 0 | 0.003 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.StartupParametersTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest` | 2 | 0 | 0 | 0 | 0.016 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest` | 5 | 0 | 0 | 0 | 0.063 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest` | 10 | 0 | 0 | 0 | 0.059 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.StageTimingTest` | 8 | 0 | 0 | 0 | 0.005 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.StageTimingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeListTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeListTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRaceDataTest` | 6 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRaceDataTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest` | 18 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest` | 26 | 0 | 0 | 0 | 0.168 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.UniqueTeamRegistrationsIntegrationTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.UniqueTeamRegistrationsIntegrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.UpstreamHandlerTest` | 13 | 0 | 0 | 0 | 0.011 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.UpstreamHandlerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataLaneTest` | 2 | 0 | 0 | 0 | 0.051 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataLaneTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest` | 8 | 0 | 0 | 0 | 0.009 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest` | 8 | 0 | 0 | 0 | 0.011 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskLaneTest` | 3 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskLaneTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrationsTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrationsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrationsTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrationsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.LogBookSyncTest` | 1 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.LogBookSyncTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamDataSyncTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamDataSyncTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductorTest` | 7 | 0 | 0 | 0 | 0.021 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest` | 4 | 0 | 0 | 0 | 0.311 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest` | 2 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest` | 3 | 0 | 0 | 0 | 1.049 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest` | 10 | 0 | 0 | 0 | 0.294 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest` | 1 | 0 | 0 | 0 | 0.288 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainerTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingContainerTest` | 5 | 0 | 0 | 0 | 0.005 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingContainerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest` | 6 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest` | 11 | 0 | 0 | 0 | 1.079 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest` | 6 | 0 | 0 | 0 | 0.328 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettingsTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettingsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest` | 5 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest` | 7 | 0 | 0 | 0 | 0.040 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInputTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInputTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest` | 7 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetricsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecuteTest` | 12 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecuteTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneTest` | 11 | 0 | 0 | 0 | 0.069 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLaneTest` | 4 | 0 | 0 | 0 | 0.251 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLaneTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneSchedulerTest` | 4 | 0 | 0 | 0 | 0.307 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneSchedulerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTaskTest` | 2 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTaskTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest` | 9 | 0 | 0 | 0 | 1.642 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest` | 8 | 0 | 0 | 0 | 0.025 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest` | 5 | 0 | 0 | 0 | 1.234 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest` | 3 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest` | 4 | 0 | 0 | 0 | 0.010 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest` | 4 | 0 | 0 | 0 | 0.027 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest` | 5 | 0 | 0 | 0 | 0.010 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest` | 1 | 0 | 0 | 0 | 0.073 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest` | 2 | 0 | 0 | 0 | 0.668 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest` | 16 | 0 | 0 | 0 | 1.728 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest` | 4 | 0 | 0 | 0 | 0.014 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest` | 9 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest` | 50 | 0 | 0 | 0 | 0.267 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest` | 1 | 0 | 0 | 0 | 0.088 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest` | 4 | 0 | 0 | 0 | 0.933 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataTest` | 2 | 0 | 0 | 0 | 0.046 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.LocationIdTest` | 3 | 0 | 0 | 0 | 0.003 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.LocationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.NodeIdTest` | 10 | 0 | 0 | 0 | 0.056 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.NodeIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest` | 3 | 0 | 0 | 0 | 0.003 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest` | 8 | 0 | 0 | 0 | 0.010 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest` | 2 | 0 | 0 | 0 | 0.021 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest` | 11 | 0 | 0 | 0 | 0.002 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest` | 19 | 0 | 0 | 0 | 0.034 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest.xml) |
| `shared/upstream-data` | `io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest` | 18 | 0 | 0 | 0 | 0.130 | [XML](shared/upstream-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest.xml) |
| `shared/upstream-data` | `io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataTest` | 4 | 0 | 0 | 0 | 0.004 | [XML](shared/upstream-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataTest.xml) |

## Failures and errors

None.

## Raw evidence

The original Surefire XML/TXT files are retained below this directory for detailed inspection.
