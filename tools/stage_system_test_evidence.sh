#!/usr/bin/env bash
# Stage retained VC-ST1 black-box evidence into the generated production tree.
#
# Expected CI context:
# - publication/ contains the checked-out prod/bld tree
# - system-test-evidence/ contains the downloaded main verification artifact
# - GITHUB_SHA and GITHUB_OUTPUT are provided by GitHub Actions
set -euo pipefail

published_revision="$(tr -d '\r\n' < publication/source-sha.txt)"
if [[ "$published_revision" != "$GITHUB_SHA" ]]; then
  echo "prod/bld already points to $published_revision; current run is $GITHUB_SHA."
  echo "A newer generated build has superseded this run; no evidence will be written."
  echo "skip=true" >> "$GITHUB_OUTPUT"
  exit 0
fi

test -d system-test-evidence/evidence
test -d system-test-evidence/surefire-reports

target="publication/evidence/tests/system-test"
rm -rf "$target"
mkdir -p "$target"
cp -a system-test-evidence/evidence/. "$target/"
cp -a system-test-evidence/surefire-reports "$target/surefire-reports"

grep -Fxq 'PASS' "$target/VC-ST1-001/result.txt"
grep -Fxq 'PASS' "$target/VC-ST1-002/result.txt"
test -s "$target/VC-ST1-002/node_A_logbook.jsonl"

cat > "$target/README.md" <<EOF
# Separate-process system verification

Retained evidence from the VC-ST1 black-box verification of source revision
\`$GITHUB_SHA\`.

- **VC-ST1-001:** PASS — executable/API/reconnect/control-shutdown baseline.
- **VC-ST1-002:** PASS — first committed registration, WebSocket reconnect and persisted LogBook recovery across a full SI-01 restart; recovered history is not emitted as a new live commit.
- \`VC-ST1-002/timing-data.jsonl\` is the actual persistence file written by SI-01 during the test.
- \`surefire-reports/\` contains the JUnit reports for both separate-process tests.

Each VC directory also retains the generated application configuration,
captured SI-01 process output and the explicit PASS/FAIL result.
EOF

python3 - <<'PY'
from pathlib import Path

tests_readme = Path("publication/evidence/tests/README.md")
text = tests_readme.read_text(encoding="utf-8").rstrip()
marker = "## Separate-process system verification"
if marker in text:
    text = text.split(marker, 1)[0].rstrip()
text += (
    "\n\n## Separate-process system verification\n\n"
    "The canonical Maven summary above covers the ordinary module tests. "
    "The separately executed process-level VC-ST1 verification is retained "
    "under [system-test/](system-test/README.md), including the real "
    "per-node LogBook persistence file produced by VC-ST1-002.\n"
)
tests_readme.write_text(text, encoding="utf-8")

root_readme = Path("publication/README.md")
root = root_readme.read_text(encoding="utf-8")
link = (
    "- [Separate-process system verification]"
    "(evidence/tests/system-test/README.md) — VC-ST1 black-box results, "
    "runtime output and persisted LogBook evidence.\n"
)
if link not in root:
    needle = (
        "- [Readable Surefire summary](evidence/tests/README.md) — "
        "aggregate test result with retained raw Surefire reports below "
        "`evidence/tests/`.\n"
    )
    if needle not in root:
        raise SystemExit("generated README does not contain the expected test summary link")
    root = root.replace(needle, needle + link)
root_readme.write_text(root, encoding="utf-8")
PY
