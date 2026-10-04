# Java tooling baseline

This repository consumes released repository and Java project tooling as immutable build dependencies.

Current Migration-006 baseline:

- `tool.git-project` semantic release: `v0.2.8`;
- `tool.git-project` exact release commit: `7c43f37e7b07cfb57638a1d1dad2501de09ba7eb`;
- `tool.java-project` semantic release: `v0.3.2`;
- `tool.java-project` exact release commit: `c0ca2e1365a64bc626ca331a8170d13340ae0b36`;
- reference-consumer baseline: `template.java-project v0.1.0`, exact `2406d362f1b93c433bb561bd8d09a9f6cde13774`.

`project.yml` uses the semantic Java tooling release because it is the human-readable dependency contract. The committed tooling gitlinks and reusable GitHub workflow callers stay on the exact immutable commits behind the reviewed releases so execution provenance is unambiguous.

Moon is consumer-owned impact declaration only. Maven remains Java build/test authority and `tool.java-project` owns canonical Linux execution, selective Windows qualification, retained Java evidence and generated `bld` finalization/publication.

Windows qualification is event-sensitive:

- pull requests use `auto` (`smoke` for ordinary Java impact, `full` for build/toolchain/workflow/platform-sensitive impact);
- ordinary protected `main` publication uses `none` because the merged revision was already qualified through its pull request;
- explicit and exact release-tag qualification uses `full`.

The released tool baseline is independently exercised by `template.java-project` before it is adopted here. This repository provides the real multi-module/product-consumer evidence while retaining its own release metadata, embedded build identity, logging-boundary checks and product release assets.
