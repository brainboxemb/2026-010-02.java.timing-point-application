# Java workflow timing

- Workflow run: `39741554961`
- Capture point: `before-generated-output-push`
- Wall clock to capture: `82 s`
- Hosted runner time to capture: `82 s`
- Started runners: `6`
- Selected Windows mode: `smoke`
- Java/Moon decision action: `3 s`
- Maven reported total time: `25.443 s`

## Jobs

| Job | Runner | Result | Duration |
| --- | --- | --- | ---: |
| release-metadata | ubuntu-24.04 | success | 5 s |
| java-production / Java affected preflight | ubuntu-24.04 | success | 10 s |
| java-production / Java execution / Linux canonical build | ubuntu-24.04 | success | 44 s |
| java-production / Canonical artifact Windows smoke / Windows canonical-artifact smoke | windows-2025 | success | 7 s |
| Packaged startup CLI verification | ubuntu-24.04 | running | 8 s |
| publish-build / Publish generated output | ubuntu-24.04 | running | 8 s |

## Steps

### release-metadata

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Checkout exact source revision | success | 1 s |
| Validate Maven version, CHANGELOG and tag semantics | success | 0 s |
| Verify failed-tag archival semantics | success | 0 s |
| Verify logging dependency boundary | success | 0 s |
| Verify black-box system-test dependency boundary | success | 0 s |
| Post Checkout exact source revision | success | 0 s |
| Complete job | success | 0 s |

### java-production / Java affected preflight

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Validate policy inputs | success | 0 s |
| Checkout exact consumer source | success | 1 s |
| Fetch exact affected-analysis base | success | 0 s |
| Checkout exact generic affected owner | success | 1 s |
| Checkout exact Java preflight owner | success | 1 s |
| Resolve Java and Windows impact once | success | 3 s |
| Upload preflight evidence | success | 1 s |
| Post Checkout exact Java preflight owner | success | 0 s |
| Post Checkout exact generic affected owner | success | 0 s |
| Post Checkout exact consumer source | success | 0 s |
| Complete job | success | 0 s |

### java-production / Java execution / Linux canonical build

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 3 s |
| Validate execution inputs | success | 0 s |
| Checkout exact consumer source | success | 1 s |
| Set up exact Java 8 baseline | success | 1 s |
| Checkout exact tool.java-project implementation | success | 1 s |
| Run canonical Java action | success | 31 s |
| Upload canonical Java artifact | success | 2 s |
| Upload prepared build publication | success | 1 s |
| Upload Linux evidence | success | 2 s |
| Post Checkout exact tool.java-project implementation | success | 0 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Post Checkout exact consumer source | success | 0 s |
| Complete job | success | 0 s |

### java-production / Canonical artifact Windows smoke / Windows canonical-artifact smoke

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Set up exact Java 8 baseline | success | 0 s |
| Download canonical Linux artifact | success | 1 s |
| Run canonical artifact on Windows | success | 2 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Complete job | success | 0 s |

### Packaged startup CLI verification

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 3 s |
| Checkout exact source revision | success | 1 s |
| Set up exact Java 8 baseline | success | 0 s |
| Download canonical packaged application | success | 2 s |
| Verify help, version and generated IF-11 example | running | 1 s |

### publish-build / Publish generated output

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Checkout exact Java publication finalizer | success | 1 s |
| Checkout exact generic publisher implementation | success | 1 s |
| Download prepared output | success | 1 s |
| Download Java preflight evidence | success | 1 s |
| Collect current workflow timing | running | 2 s |
