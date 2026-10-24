# Java workflow timing

- Workflow run: `40128476979`
- Capture point: `before-generated-output-push`
- Wall clock to capture: `78 s`
- Hosted runner time to capture: `81 s`
- Started runners: `6`
- Selected Windows mode: `none`
- Java/Moon decision action: `3 s`
- Maven reported total time: `26.690 s`

## Jobs

| Job | Runner | Result | Duration |
| --- | --- | --- | ---: |
| release-metadata | ubuntu-24.04 | success | 4 s |
| java-production / Java affected preflight | ubuntu-24.04 | success | 13 s |
| java-production / Java execution / Linux canonical build | ubuntu-24.04 | success | 50 s |
| Packaged startup CLI verification | ubuntu-24.04 | running | 4 s |
| Main black-box system verification | ubuntu-24.04 | running | 5 s |
| publish-build / Publish generated output | ubuntu-24.04 | running | 5 s |

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
| Fetch exact affected-analysis base | success | 1 s |
| Checkout exact generic affected owner | success | 0 s |
| Checkout exact Java preflight owner | success | 1 s |
| Resolve Java and Windows impact once | success | 3 s |
| Upload preflight evidence | success | 1 s |
| Post Checkout exact Java preflight owner | success | 1 s |
| Post Checkout exact generic affected owner | success | 0 s |
| Post Checkout exact consumer source | success | 0 s |
| Complete job | success | 0 s |

### java-production / Java execution / Linux canonical build

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Validate execution inputs | success | 0 s |
| Checkout exact consumer source | success | 1 s |
| Set up exact Java 8 baseline | success | 3 s |
| Checkout exact tool.java-project implementation | success | 1 s |
| Run canonical Java action | success | 33 s |
| Upload canonical Java artifact | success | 2 s |
| Upload prepared build publication | success | 2 s |
| Upload Linux evidence | success | 3 s |
| Post Checkout exact tool.java-project implementation | success | 0 s |
| Post Set up exact Java 8 baseline | success | 0 s |
| Post Checkout exact consumer source | success | 0 s |
| Complete job | success | 0 s |

### Packaged startup CLI verification

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 2 s |
| Checkout exact source revision | running | 2 s |

### Main black-box system verification

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 2 s |
| Checkout exact integrated revision | running | 2 s |

### publish-build / Publish generated output

| Step | Result | Duration |
| --- | --- | ---: |
| Set up job | success | 1 s |
| Checkout exact Java publication finalizer | success | 0 s |
| Checkout exact generic publisher implementation | success | 1 s |
| Download prepared output | success | 1 s |
| Download Java preflight evidence | success | 0 s |
| Collect current workflow timing | running | 1 s |
