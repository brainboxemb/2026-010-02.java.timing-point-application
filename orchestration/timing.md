# Java workflow timing

- Workflow run: `37193406905`
- Capture point: `before-generated-output-push`
- Wall clock to capture: `80 s`
- Hosted runner time to capture: `202 s`
- Started runners: `7`
- Maven reported total time: `13.643 s`

## Jobs

| Job | Runner | Result | Duration |
| --- | --- | --- | ---: |
| release-metadata | ubuntu-24.04 | success | 6 s |
| Release black-box system verification (Windows) | windows-2025 | success | 61 s |
| Release black-box system verification (Linux) | ubuntu-24.04 | success | 34 s |
| release-windows-full / Windows compatibility build | windows-2025 | success | 61 s |
| release-linux / Linux canonical build | ubuntu-24.04 | success | 27 s |
| release-windows-smoke / Windows canonical-artifact smoke | windows-2025 | success | 7 s |
| release-publish-build / Publish generated output | ubuntu-24.04 | running | 6 s |

## Steps

### release-metadata

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 0 s |
| Checkout exact source revision | success | 1 s |
| Validate Maven version, CHANGELOG and tag semantics | success | 0 s |
| Verify failed-tag archival semantics | success | 1 s |
| Verify logging dependency boundary | success | 0 s |
| Verify black-box system-test dependency boundary | success | 0 s |
| Post Checkout exact source revision | success | 0 s |
| Complete job | success | 0 s |

### Release black-box system verification (Windows)

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Checkout exact release revision | success | 8 s |
| Set up exact Java 8 baseline | success | 0 s |
| Run VC-ST1 black-box system verification | success | 45 s |
| Upload Windows system-test evidence | success | 1 s |
| Post Set up exact Java 8 baseline | success | 2 s |
| Post Checkout exact release revision | success | 2 s |
| Complete job | success | 0 s |

### Release black-box system verification (Linux)

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 2 s |
| Checkout exact release revision | success | 0 s |
| Set up exact Java 8 baseline | success | 2 s |
| Run VC-ST1 black-box system verification | success | 26 s |
| Upload Linux system-test evidence | success | 1 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Post Checkout exact release revision | success | 1 s |
| Complete job | success | 0 s |

### release-windows-full / Windows compatibility build

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 2 s |
| Validate exact source when supplied | success | 0 s |
| Checkout exact consumer source | success | 11 s |
| Set up exact Java 8 baseline | success | 1 s |
| Validate Maven Wrapper baseline | success | 9 s |
| Verify | success | 28 s |
| Upload Windows test evidence | success | 2 s |
| Post Set up exact Java 8 baseline | success | 4 s |
| Post Checkout exact consumer source | success | 2 s |
| Complete job | success | 0 s |

### release-linux / Linux canonical build

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Validate execution inputs | success | 0 s |
| Checkout exact consumer source | success | 1 s |
| Set up exact Java 8 baseline | success | 1 s |
| Checkout exact tool.java-project implementation | success | 0 s |
| Run canonical Java action | success | 19 s |
| Upload canonical Java artifact | success | 1 s |
| Upload prepared build publication | success | 1 s |
| Upload Linux evidence | success | 1 s |
| Post Checkout exact tool.java-project implementation | success | 0 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Post Checkout exact consumer source | success | 1 s |
| Complete job | success | 0 s |

### release-windows-smoke / Windows canonical-artifact smoke

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Set up exact Java 8 baseline | success | 0 s |
| Download canonical Linux artifact | success | 1 s |
| Run canonical artifact on Windows | success | 2 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Complete job | success | 0 s |

### release-publish-build / Publish generated output

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Checkout exact Java publication finalizer | success | 1 s |
| Checkout exact generic publisher implementation | success | 1 s |
| Download prepared output | success | 1 s |
| Download Java preflight evidence | skipped | 0 s |
| Collect current workflow timing | running | 1 s |
