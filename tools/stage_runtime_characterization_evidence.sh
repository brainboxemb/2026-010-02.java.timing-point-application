#!/usr/bin/env bash
# Stage one V01 development-host evidence set into the generated characterization branch.
#
# Expected CI context:
# - publication/ contains the checked-out prod/characterization branch
# - runtime-characterization-evidence/ contains the downloaded JSON evidence
# - GITHUB_SHA is the exact characterized source revision
set -euo pipefail

evidence_source="runtime-characterization-evidence"
test -d "${evidence_source}"

if [[ ! -f publication/.runtime-characterization-evidence-root ]]; then
  (
    cd publication
    git rm -r --quiet --ignore-unmatch -- .
  )
  touch publication/.runtime-characterization-evidence-root
fi

target="publication/v01/development-host/${GITHUB_SHA}"
rm -rf "${target}"
mkdir -p "${target}"
cp -a "${evidence_source}/." "${target}/"

json_count="$(find "${target}" -maxdepth 1 -type f -name '*.json' | wc -l | tr -d ' ')"
if [[ "${json_count}" != "12" ]]; then
  echo "Expected 12 retained V01 JSON summaries, found ${json_count}" >&2
  exit 1
fi

for case_name in steady burst history-1000 history-9999; do
  for repetition in 1 2 3; do
    file="${target}/${case_name}-r${repetition}.json"
    test -s "${file}"
    grep -F '"outcome" : "PASS"' "${file}"
  done
done

cat > "${target}/README.md" <<EOF
# V01 development-host runtime characterization

Retained engineering evidence for source revision
`${GITHUB_SHA}`.

The baseline contains three repetitions of each workload:

- `steady`: 100 measured observations paced at 20 registrations/s;
- `burst`: 100 measured observations delivered without pacing;
- `history-1000`: paced workload after 1,000 preloaded committed records;
- `history-9999`: paced workload after 9,999 preloaded committed records.

Each run uses 20 warm-up observations and a bounded history query limit of 100.
The JSON files contain their own JVM/OS identity, source/build identity, queue,
TimingNode/TagProcessor measurements, JVM observations and history-query result.

This is development-host engineering evidence, not a product performance limit
and not Raspberry Pi target evidence.
EOF

cat > publication/README.md <<EOF
# Runtime characterization evidence

Generated engineering evidence retained separately from canonical product build output.

- V01 development-host runs are stored under `v01/development-host/<source-sha>/`.
- Each source revision contains the complete repeatable steady/burst/growing-history baseline.
- Target-hardware evidence is kept separate from development-host evidence.
EOF
