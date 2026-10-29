# Unit test report

Generated from the Maven Surefire XML produced by the canonical `mvn verify` run. Tests are **not** rerun to create this report.

## Summary

**Status: PASS**

| Tests | Failures | Errors | Skipped | Time (s) |
| ---: | ---: | ---: | ---: | ---: |
| 609 | 0 | 0 | 0 | 16.982 |

## Maven modules

| Module | Tests | Failures | Errors | Skipped | Time (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app` | 23 | 0 | 0 | 0 | 0.510 |
| `core` | 487 | 0 | 0 | 0 | 16.230 |
| `shared/event-data` | 12 | 0 | 0 | 0 | 0.032 |
| `shared/timing-data` | 71 | 0 | 0 | 0 | 0.107 |
| `shared/upstream-data` | 16 | 0 | 0 | 0 | 0.103 |

## Test suites

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) | Raw XML |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `app` | `oss.relay.timing.timingpoint.app.ExampleConfigurationTest` | 2 | 0 | 0 | 0 | 0.078 | [XML](app/target/surefire-reports/TEST-oss.relay.timing.timingpoint.app.ExampleConfigurationTest.xml) |
| `app` | `oss.relay.timing.timingpoint.app.MainTest` | 8 | 0 | 0 | 0 | 0.018 | [XML](app/target/surefire-reports/TEST-oss.relay.timing.timingpoint.app.MainTest.xml) |
| `app` | `oss.relay.timing.timingpoint.app.StartupCommandLineTest` | 10 | 0 | 0 | 0 | 0.003 | [XML](app/target/surefire-reports/TEST-oss.relay.timing.timingpoint.app.StartupCommandLineTest.xml) |
| `app` | `oss.relay.timing.timingpoint.app.StartupParametersTest` | 2 | 0 | 0 | 0 | 0.376 | [XML](app/target/surefire-reports/TEST-oss.relay.timing.timingpoint.app.StartupParametersTest.xml) |
| `app` | `oss.relay.timing.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest` | 1 | 0 | 0 | 0 | 0.035 | [XML](app/target/surefire-reports/TEST-oss.relay.timing.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest.xml) |
| `core` | `oss.relay.timing.timingpoint.application.ApplicationConductorTest` | 2 | 0 | 0 | 0 | 0.026 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.application.ApplicationConductorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.application.ConfigurationControlTest` | 7 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.application.ConfigurationControlTest.xml) |
| `core` | `oss.relay.timing.timingpoint.application.PresentationGatewayTest` | 10 | 0 | 0 | 0 | 0.019 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.application.PresentationGatewayTest.xml) |
| `core` | `oss.relay.timing.timingpoint.application.TimingNodeStatusTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.application.TimingNodeStatusTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.ActiveRegistrationsIntegrationTest` | 6 | 0 | 0 | 0 | 0.031 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.ActiveRegistrationsIntegrationTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.NodeUpstreamTest` | 17 | 0 | 0 | 0 | 0.065 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.NodeUpstreamTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.TimingNodeCurrentStateTest` | 8 | 0 | 0 | 0 | 0.011 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.TimingNodeCurrentStateTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.TimingNodeListTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.TimingNodeListTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.TimingNodeRegistrationTest` | 19 | 0 | 0 | 0 | 0.017 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.TimingNodeRegistrationTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.TimingNodeTest` | 26 | 0 | 0 | 0 | 0.247 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.TimingNodeTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.logbook.LogBookDataTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.logbook.LogBookDataTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest` | 8 | 0 | 0 | 0 | 2.474 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest` | 7 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.processing.TagObservationFilterTest` | 5 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.processing.TagObservationFilterTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.processing.TagProcessingMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.processing.TagProcessingMetricsTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.processing.TagProcessorTest` | 11 | 0 | 0 | 0 | 0.017 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.processing.TagProcessorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.racedata.RaceDataStateTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.racedata.RaceDataStateTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrationsTest` | 11 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrationsTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.registration.CompetitionRankingTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.registration.CompetitionRankingTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.registration.RegisteredTeamsTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.registration.RegisteredTeamsTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.node.registration.StageTimingTest` | 9 | 0 | 0 | 0 | 0.012 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.node.registration.StageTimingTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.system.TimingSystemConductorTest` | 7 | 0 | 0 | 0 | 0.020 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.system.TimingSystemConductorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.system.upstream.node.DownstreamDeliveryTest` | 5 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.system.upstream.node.DownstreamDeliveryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.system.upstream.node.NodeUpstreamLogSummaryTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.system.upstream.node.NodeUpstreamLogSummaryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.system.upstream.node.TimingDataReplayTaskTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.system.upstream.node.TimingDataReplayTaskTest.xml) |
| `core` | `oss.relay.timing.timingpoint.domain.system.upstream.node.TimingDataSyncTest` | 5 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.domain.system.upstream.node.TimingDataSyncTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.BuildIdentityTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.BuildIdentityTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.extension.ExtensionRegistryTest` | 4 | 0 | 0 | 0 | 0.236 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.extension.ExtensionRegistryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.lifecycle.AbstractConductorTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.lifecycle.AbstractConductorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest` | 6 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.logging.ConsolePromptCoordinatorTest` | 3 | 0 | 0 | 0 | 1.050 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.logging.ConsolePromptCoordinatorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.logging.LoggingTest` | 10 | 0 | 0 | 0 | 0.280 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.logging.LoggingTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.loggingserver.LoggingServerTest` | 1 | 0 | 0 | 0 | 0.255 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.loggingserver.LoggingServerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.property.PropertyContainerTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.property.PropertyContainerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.setting.SettingTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.setting.SettingTest.xml) |
| `core` | `oss.relay.timing.timingpoint.infra.validation.ChecksTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.infra.validation.ChecksTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.devices.antenna.AntennaIdTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.devices.antenna.AntennaIdTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.devices.antenna.TagObservationTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.devices.antenna.TagObservationTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaManagerTest` | 11 | 0 | 0 | 0 | 1.076 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaManagerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest` | 6 | 0 | 0 | 0 | 0.305 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.devices.antenna.model.SimulatedAntennaTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.devices.antenna.model.SimulatedAntennaTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.messaging.UpstreamGatewayTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.messaging.UpstreamGatewayTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.messaging.engineering.EngineerApiConnectorTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.messaging.engineering.EngineerApiConnectorTest.xml) |
| `core` | `oss.relay.timing.timingpoint.io.storage.FileAppendOnlyRecordStoreTest` | 9 | 0 | 0 | 0 | 0.006 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.io.storage.FileAppendOnlyRecordStoreTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.environment.PlatformEnvironmentTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.environment.PlatformEnvironmentTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.events.EventInputTest` | 1 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.events.EventInputTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.events.EventTest` | 7 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.events.EventTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.events.EventWithMetricsTest` | 1 | 0 | 0 | 0 | 0.006 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.events.EventWithMetricsTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.execution.SafeExecuteTest` | 12 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.execution.SafeExecuteTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.execution.lanes.SerialLaneTest` | 11 | 0 | 0 | 0 | 0.070 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.execution.lanes.SerialLaneTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.execution.tasks.AbstractTaskTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.execution.tasks.AbstractTaskTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunnerTest` | 11 | 0 | 0 | 0 | 0.319 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunnerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.memory.ReusableObjectPoolTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.memory.ReusableObjectPoolTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.metrics.RuntimeObservationTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.metrics.RuntimeObservationTest.xml) |
| `core` | `oss.relay.timing.timingpoint.platform.time.ClockTimeSourceTest` | 1 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.platform.time.ClockTimeSourceTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.common.terminal.TerminalSessionTest` | 9 | 0 | 0 | 0 | 0.311 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.common.terminal.TerminalSessionTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.api.HttpEndpointTest` | 10 | 0 | 0 | 0 | 2.046 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.api.HttpEndpointTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.api.HttpRequestReaderTest` | 8 | 0 | 0 | 0 | 0.006 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.api.HttpRequestReaderTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.api.MessageWriterEventTimeTest` | 1 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.api.MessageWriterEventTimeTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.api.WebSocketEndpointTest` | 6 | 0 | 0 | 0 | 1.529 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.api.WebSocketEndpointTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest` | 3 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.console.LocalConsoleTest` | 7 | 0 | 0 | 0 | 0.007 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.console.LocalConsoleTest.xml) |
| `core` | `oss.relay.timing.timingpoint.presentation.interfaces.shell.RemoteShellServerTest` | 6 | 0 | 0 | 0 | 0.016 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.presentation.interfaces.shell.RemoteShellServerTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.ApplicationExitSignalTest` | 3 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.ApplicationExitSignalTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.RuntimeExecutorsTest` | 5 | 0 | 0 | 0 | 0.009 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.RuntimeExecutorsTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.RuntimeTimeSourcesTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.RuntimeTimeSourcesTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.SimulationRuntimeRecoveryTest` | 1 | 0 | 0 | 0 | 0.062 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.SimulationRuntimeRecoveryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.SimulationRuntimeScenarioTest` | 2 | 0 | 0 | 0 | 0.669 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.SimulationRuntimeScenarioTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.TimingApplicationRuntimeTest` | 18 | 0 | 0 | 0 | 3.176 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.TimingApplicationRuntimeTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.TimingSystemCompositionTest` | 4 | 0 | 0 | 0 | 0.015 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.TimingSystemCompositionTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.config.AntennaManagerConfigRegistryTest` | 2 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.config.AntennaManagerConfigRegistryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.config.TimingSystemConfigRegistryTest` | 9 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.config.TimingSystemConfigRegistryTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.config.YamlLoaderTest` | 56 | 0 | 0 | 0 | 0.362 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.config.YamlLoaderTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.configuration.ApplicationConfigurationTest` | 5 | 0 | 0 | 0 | 0.063 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.configuration.ApplicationConfigurationTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest` | 1 | 0 | 0 | 0 | 0.186 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest.xml) |
| `core` | `oss.relay.timing.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest` | 5 | 0 | 0 | 0 | 1.182 | [XML](core/target/surefire-reports/TEST-oss.relay.timing.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest.xml) |
| `shared/event-data` | `oss.relay.timing.eventdata.EventDataProviderTest` | 2 | 0 | 0 | 0 | 0.032 | [XML](shared/event-data/target/surefire-reports/TEST-oss.relay.timing.eventdata.EventDataProviderTest.xml) |
| `shared/event-data` | `oss.relay.timing.eventdata.EventDataTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](shared/event-data/target/surefire-reports/TEST-oss.relay.timing.eventdata.EventDataTest.xml) |
| `shared/event-data` | `oss.relay.timing.eventdata.TagIdTest` | 8 | 0 | 0 | 0 | 0.000 | [XML](shared/event-data/target/surefire-reports/TEST-oss.relay.timing.eventdata.TagIdTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.LocationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.LocationIdTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.NodeIdTest` | 10 | 0 | 0 | 0 | 0.043 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.NodeIdTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.RegistrationIdTest` | 7 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.RegistrationIdTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.TimingDataCodecTest` | 4 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.TimingDataCodecTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.TimingDataProviderTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.TimingDataProviderTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.TimingRecordTest` | 13 | 0 | 0 | 0 | 0.005 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.TimingRecordTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.TimingTimestampTest` | 11 | 0 | 0 | 0 | 0.003 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.TimingTimestampTest.xml) |
| `shared/timing-data` | `oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodecTest` | 21 | 0 | 0 | 0 | 0.055 | [XML](shared/timing-data/target/surefire-reports/TEST-oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodecTest.xml) |
| `shared/upstream-data` | `oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest` | 10 | 0 | 0 | 0 | 0.060 | [XML](shared/upstream-data/target/surefire-reports/TEST-oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest.xml) |
| `shared/upstream-data` | `oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataTest` | 6 | 0 | 0 | 0 | 0.043 | [XML](shared/upstream-data/target/surefire-reports/TEST-oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataTest.xml) |

## Failures and errors

None.

## Raw evidence

The original Surefire XML/TXT files are retained below this directory for detailed inspection.

## Separate-process system verification

The canonical Maven summary above covers the ordinary module tests. The separately executed process-level VC-ST1 verification is retained under [system-test/](system-test/README.md), including the real per-node LogBook persistence file produced by VC-ST1-002.
