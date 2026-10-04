# Development

## Start here

Project requirements, architecture and SIP planning live in the
[meta repository](https://github.com/brainboxemb/2026-010-01.meta.event-timing-software).

Shared BrainboxEmb repository/agent conventions come from
[brainboxemb.meta](https://github.com/brainboxemb/brainboxemb.meta).

## Clone and bootstrap

A normal clone does not require `--recurse-submodules`.

Linux/POSIX:

```bash
git clone https://github.com/brainboxemb/2026-010-02.java.timing-point-application.git
cd 2026-010-02.java.timing-point-application
./bootstrap.sh
./mvnw verify
```

Windows:

```powershell
git clone https://github.com/brainboxemb/2026-010-02.java.timing-point-application.git
cd 2026-010-02.java.timing-point-application
.\bootstrap.ps1
.\mvnw.cmd verify
```

Use `update-repo.sh` / `update-repo.ps1` only when deliberately moving declared
tooling/dependency releases.

## Build and test loop

Normal product verification:

```bash
./mvnw verify
```

Windows:

```powershell
.\mvnw.cmd verify
```

Formal black-box SI-01 verification is explicit:

```bash
./mvnw verify -Psystem-test
```

The system-test profile starts the packaged application as a separate JVM and uses only
supported external interfaces.

## NetBeans / Windows

Open the repository root as the Maven project after bootstrap.

Repository `nbactions.xml` keeps IDE Build/Clean Build lightweight by skipping
tests. Run/Debug first install the current reactor sources with tests skipped and then
start the app module with `config/application.yml`, so the executable uses the sibling
core from the same checkout.

For a simple run check, use:

```text
help
version
status
quit
```

## Tooling ownership

This repository consumes:
- `tool.git-project` for generic repository lifecycle/bootstrap;
- `tool.java-project` for canonical Java build/test/evidence behaviour.

Exact consumed versions are documented in
[20-10-tooling-baseline.md](20-10-tooling-baseline.md) and are ultimately defined by
the committed gitlinks, `project.yml` and workflow refs.

Maven remains Java build/test authority. Moon declares impact only.

## CI and generated output

Repository build evidence is published as:
- pull request N -> `dev/pr-N/bld`;
- main -> `prod/bld`;
- release tag -> `rel/vX.Y.Z/bld`.

The canonical Linux producer owns product artifacts, tests and toolchain/build
provenance. Windows qualification is selected by event/impact; release-tag qualification
always performs the required full cross-platform checks.

See [50-00-verification.md](50-00-verification.md) for verification/evidence semantics.

## Release workflow

Development normally uses a Maven `-SNAPSHOT` version.

A release-preparation PR:
1. sets the complete reactor to the intended non-SNAPSHOT version;
2. moves changelog content into the matching release section;
3. passes normal PR qualification.

After merge, the exact tagged commit is qualified again. A valid release aligns Maven
version, changelog heading, Git tag and embedded BuildIdentity. Failed tagged candidates
follow the repository's `-failed` archival policy and the consumed version is not reused.

After a successful release, a separate normal PR advances main to the next planned
snapshot version.

## Public repository rule

Keep production identities, proprietary protocols, credentials, keys and private mappings
out of this public repository.
