# V01 development-host runtime characterization

Retained engineering evidence for source revision
.

The baseline contains three repetitions of each workload:

- : 100 measured observations paced at 20 registrations/s;
- : 100 measured observations delivered without pacing;
- : paced workload after 1,000 preloaded committed records;
- : paced workload after 9,999 preloaded committed records.

Each run uses 20 warm-up observations and a bounded history query limit of 100.
The JSON files contain their own JVM/OS identity, source/build identity, queue,
TimingNode/TagProcessor measurements, JVM observations and history-query result.

This is development-host engineering evidence, not a product performance limit
and not Raspberry Pi target evidence.
