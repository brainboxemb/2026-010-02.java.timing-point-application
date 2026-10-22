# Repository agent guidance

Start with [README.md](README.md) for this repository's implementation scope and
current Java/module structure.

For shared BrainboxEmb working conventions, read
[brainboxemb.meta/AGENTS.md](https://github.com/brainboxemb/brainboxemb.meta/blob/main/AGENTS.md).
That shared entrypoint owns the current generic Git/commit/PR/CI workflow and
routes to shared repository-tooling and software/Java guidance.

Do not inherit `AGENTS.md` from pinned tools or dependencies as working policy
for this repository. Exact dependency behaviour comes from this repository's
configuration/gitlinks/immutable workflow refs plus the pinned dependency's
README, docs, source and tests.

## Local technical entrypoints

Use these local/project-family authorities:

- [README.md](README.md) — implementation scope, modules/packages, lifecycle and build/release overview;
- [docs/tooling-baseline.md](docs/tooling-baseline.md) — accepted tooling revisions and Java execution baseline;
- [project.yml](project.yml) / [project.java.yml](project.java.yml) — declared repository/Java tooling configuration;
- [companion meta repository](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software) — project-wide requirements, architecture, interface design, planning and verification coordination;
- [SI-01 SAD](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/31-01-SAD-timing-application-architecture.md) — headless timing application architecture;
- [Java component SDD](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/31-01-SDD-02-java-component-design.md) — Java package/component/artifact design;
- [Java design rules](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/44-01-GPD-java-design-rules.md) — durable Java coding/design conventions for ownership, events, comments, naming, composition, layout and tests;
- [Engineering Client design rules](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software/blob/main/docs/44-02-GPD-engineering-client-design-rules.md) — SI-02 JavaFX/BentoFX layout, interaction, presentation-state and asynchronous UI conventions.

Use source/tests and live CI/generated evidence for exact current implementation
state. Durable Java and Engineering Client design rules belong in the GPD documents
above; do not duplicate them in this repository-routing file.

## Repository boundaries

This repository owns the public Java implementation for SI-01 plus executable
consumer composition.

Keep product implementation detail here and project-family coordination in the
companion meta repository.

The current implementation intentionally keeps one reusable
`timing-point-core` application-core library and one runnable `timing-point-app` consumer.
Architecture packages/layers are not automatically Maven artifact boundaries;
introduce another artifact only when a concrete reuse, dependency, deployment,
ownership, public/private or release boundary justifies it.

Application composition/startup/shutdown belongs in the executable application.
Reusable timing/domain rules and application-core contracts belong in the core module
according to the current README and owning SAD/SDD.

## Local constraints

- keep Java 8 compatibility until the project architecture/tooling baseline explicitly changes;
- use the repository Maven Wrapper;
- keep real/proprietary deployment identities, protocol mappings, credentials and secrets out of this public repository;
- treat legacy/reference software, production behaviour and user-provided operational examples as reference input rather than automatically as public contract data; publish concrete categories, ranges, encodings or mappings only when an explicit public project authority owns them;
- Docker is not a prerequisite for normal compile/unit-test work.

When architecture intent and current implementation differ, treat the project
documentation in the companion meta repository as the design authority and make
implementation migration explicit rather than silently letting old class/package
names redefine the architecture.
