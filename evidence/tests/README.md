# Unit test report

Generated from the Maven Surefire XML produced by the canonical `mvn verify` run. Tests are **not** rerun to create this report.

## Summary

**Status: PASS**

| Tests | Failures | Errors | Skipped | Time (s) |
| ---: | ---: | ---: | ---: | ---: |
| 581 | 0 | 0 | 0 | 12.337 |

## Maven modules

| Module | Tests | Failures | Errors | Skipped | Time (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app` | 22 | 0 | 0 | 0 | 0.172 |
| `core` | 461 | 0 | 0 | 0 | 11.886 |
| `shared/event-data` | 12 | 0 | 0 | 0 | 0.057 |
| `shared/timing-data` | 71 | 0 | 0 | 0 | 0.111 |
| `shared/upstream-data` | 15 | 0 | 0 | 0 | 0.111 |

## Test suites

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) | Raw XML |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest` | 2 | 0 | 0 | 0 | 0.034 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.ExampleConfigurationTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.MainTest` | 8 | 0 | 0 | 0 | 0.128 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.MainTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest` | 10 | 0 | 0 | 0 | 0.005 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.StartupCommandLineTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.StartupParametersTest` | 1 | 0 | 0 | 0 | 0.004 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.StartupParametersTest.xml) |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest` | 2 | 0 | 0 | 0 | 0.009 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest` | 5 | 0 | 0 | 0 | 0.061 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControlTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest` | 10 | 0 | 0 | 0 | 0.084 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest` | 3 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatusTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.ActiveRegistrationsIntegrationTest` | 5 | 0 | 0 | 0 | 0.040 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.ActiveRegistrationsIntegrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.NodeUpstreamTest` | 16 | 0 | 0 | 0 | 0.060 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.NodeUpstreamTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeListTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeListTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRaceDataTest` | 7 | 0 | 0 | 0 | 0.011 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRaceDataTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest` | 19 | 0 | 0 | 0 | 0.015 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeRegistrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest` | 26 | 0 | 0 | 0 | 0.246 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookDataTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest` | 8 | 0 | 0 | 0 | 0.010 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistenceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest` | 7 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.RegistrationDuplicateFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest` | 5 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagObservationFilterTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetricsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest` | 11 | 0 | 0 | 0 | 0.021 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataStateTest` | 3 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataStateTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrationsTest` | 10 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrationsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeamsTest` | 4 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeamsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.StageTimingTest` | 9 | 0 | 0 | 0 | 0.019 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.StageTimingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductorTest` | 7 | 0 | 0 | 0 | 0.026 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.DownstreamDeliveryTest` | 5 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.DownstreamDeliveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstreamLogSummaryTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstreamLogSummaryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.TimingDataReplayTaskTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.TimingDataReplayTaskTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.TimingDataSyncTest` | 2 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.TimingDataSyncTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest` | 4 | 0 | 0 | 0 | 0.284 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest` | 1 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest` | 6 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest` | 3 | 0 | 0 | 0 | 1.050 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptCoordinatorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest` | 10 | 0 | 0 | 0 | 0.299 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest` | 1 | 0 | 0 | 0 | 0.272 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainerTest` | 5 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest` | 5 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.setting.SettingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.validation.ChecksTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest` | 6 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaIdTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservationTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest` | 11 | 0 | 0 | 0 | 1.088 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest` | 6 | 0 | 0 | 0 | 0.317 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaMultiplexTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest` | 5 | 0 | 0 | 0 | 0.005 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntennaTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGatewayTest` | 4 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGatewayTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering.EngineerApiConnectorTest` | 3 | 0 | 0 | 0 | 0.006 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering.EngineerApiConnectorTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest` | 7 | 0 | 0 | 0 | 0.030 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironmentTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInputTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInputTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest` | 7 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetricsTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetricsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecuteTest` | 12 | 0 | 0 | 0 | 0.005 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecuteTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneTest` | 11 | 0 | 0 | 0 | 0.069 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTaskTest` | 2 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTaskTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunnerTest` | 11 | 0 | 0 | 0 | 0.321 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunnerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.memory.ReusableObjectPoolTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.memory.ReusableObjectPoolTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest` | 1 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSourceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal.TerminalSessionTest` | 5 | 0 | 0 | 0 | 0.013 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal.TerminalSessionTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest` | 10 | 0 | 0 | 0 | 2.012 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest` | 8 | 0 | 0 | 0 | 0.022 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.MessageWriterEventTimeTest` | 1 | 0 | 0 | 0 | 0.005 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.MessageWriterEventTimeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest` | 6 | 0 | 0 | 0 | 1.483 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest` | 3 | 0 | 0 | 0 | 0.012 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketOutboundDeliveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest` | 7 | 0 | 0 | 0 | 0.021 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest` | 5 | 0 | 0 | 0 | 0.023 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.ApplicationExitSignalTest` | 2 | 0 | 0 | 0 | 0.003 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.ApplicationExitSignalTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest` | 5 | 0 | 0 | 0 | 0.006 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeExecutorsTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.RuntimeTimeSourcesTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest` | 1 | 0 | 0 | 0 | 0.069 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeRecoveryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest` | 2 | 0 | 0 | 0 | 0.662 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.SimulationRuntimeScenarioTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest` | 17 | 0 | 0 | 0 | 1.728 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntimeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest` | 4 | 0 | 0 | 0 | 0.012 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingSystemCompositionTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest` | 2 | 0 | 0 | 0 | 0.000 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest` | 9 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.TimingSystemConfigRegistryTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest` | 54 | 0 | 0 | 0 | 0.181 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest` | 5 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfigurationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest` | 1 | 0 | 0 | 0 | 0.063 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest` | 5 | 0 | 0 | 0 | 1.178 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunnerTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest` | 2 | 0 | 0 | 0 | 0.003 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataProviderTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.EventDataTest` | 2 | 0 | 0 | 0 | 0.001 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.EventDataTest.xml) |
| `shared/event-data` | `io.github.brainboxemb.eventtiming.eventdata.TagIdTest` | 8 | 0 | 0 | 0 | 0.053 | [XML](shared/event-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.eventdata.TagIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.LocationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.LocationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.NodeIdTest` | 10 | 0 | 0 | 0 | 0.049 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.NodeIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest` | 7 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest` | 4 | 0 | 0 | 0 | 0.011 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest` | 2 | 0 | 0 | 0 | 0.011 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingRecordTest` | 13 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingRecordTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest` | 11 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest` | 21 | 0 | 0 | 0 | 0.037 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest.xml) |
| `shared/upstream-data` | `io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest` | 9 | 0 | 0 | 0 | 0.111 | [XML](shared/upstream-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodecTest.xml) |
| `shared/upstream-data` | `io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataTest` | 6 | 0 | 0 | 0 | 0.000 | [XML](shared/upstream-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataTest.xml) |

## Failures and errors

None.

## Raw evidence

The original Surefire XML/TXT files are retained below this directory for detailed inspection.
