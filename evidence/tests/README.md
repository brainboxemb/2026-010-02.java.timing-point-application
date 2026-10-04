# Unit test report

Generated from the Maven Surefire XML produced by the canonical `mvn verify` run. Tests are **not** rerun to create this report.

## Summary

**Status: PASS**

| Tests | Failures | Errors | Skipped | Time (s) |
| ---: | ---: | ---: | ---: | ---: |
| 158 | 0 | 0 | 0 | 3.928 |

## Maven modules

| Module | Tests | Failures | Errors | Skipped | Time (s) |
| --- | ---: | ---: | ---: | ---: | ---: |
| `app` | 1 | 0 | 0 | 0 | 0.045 |
| `core` | 114 | 0 | 0 | 0 | 3.760 |
| `shared/timing-data` | 43 | 0 | 0 | 0 | 0.123 |

## Test suites

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) | Raw XML |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| `app` | `io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest` | 1 | 0 | 0 | 0 | 0.045 | [XML](app/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.app.bootstrap.EmbeddedBuildIdentityLoaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationStatusTest` | 2 | 0 | 0 | 0 | 0.046 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationStatusTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest` | 6 | 0 | 0 | 0 | 0.194 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGatewayTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBookTest` | 4 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBookTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeRegistrationTest` | 13 | 0 | 0 | 0 | 0.620 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeRegistrationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTest` | 10 | 0 | 0 | 0 | 0.388 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistenceTest` | 8 | 0 | 0 | 0 | 0.013 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistenceTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest` | 3 | 0 | 0 | 0 | 0.001 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentityTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest` | 7 | 0 | 0 | 0 | 0.043 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest` | 1 | 0 | 0 | 0 | 0.030 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest` | 7 | 0 | 0 | 0 | 0.041 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStoreTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest` | 6 | 0 | 0 | 0 | 0.004 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialWorkerTest` | 6 | 0 | 0 | 0 | 0.157 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialWorkerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest` | 4 | 0 | 0 | 0 | 0.887 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest` | 3 | 0 | 0 | 0 | 0.030 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpRequestReaderTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest` | 3 | 0 | 0 | 0 | 0.762 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpointTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest` | 3 | 0 | 0 | 0 | 0.158 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsoleTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest` | 2 | 0 | 0 | 0 | 0.106 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServerTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.ApplicationTest` | 4 | 0 | 0 | 0 | 0.002 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.ApplicationTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.CompositionTest` | 3 | 0 | 0 | 0 | 0.105 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.CompositionTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.LifecycleTest` | 6 | 0 | 0 | 0 | 0.051 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.LifecycleTest.xml) |
| `core` | `io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest` | 13 | 0 | 0 | 0 | 0.121 | [XML](core/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoaderTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.LocationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.LocationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.NodeIdTest` | 3 | 0 | 0 | 0 | 0.048 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.NodeIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest` | 3 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.RegistrationIdTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest` | 3 | 0 | 0 | 0 | 0.003 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataCodecTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest` | 8 | 0 | 0 | 0 | 0.008 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataFactoryTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest` | 1 | 0 | 0 | 0 | 0.000 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataProviderTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingDataTest` | 6 | 0 | 0 | 0 | 0.001 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingDataTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest` | 6 | 0 | 0 | 0 | 0.008 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.TimingTimestampTest.xml) |
| `shared/timing-data` | `io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest` | 10 | 0 | 0 | 0 | 0.055 | [XML](shared/timing-data/target/surefire-reports/TEST-io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodecTest.xml) |

## Failures and errors

None.

## Raw evidence

The original Surefire XML/TXT files are retained below this directory for detailed inspection.
