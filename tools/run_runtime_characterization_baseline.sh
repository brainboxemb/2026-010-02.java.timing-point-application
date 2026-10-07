#!/usr/bin/env bash
# Run the repeatable V01 development-host runtime-characterization baseline.
set -euo pipefail

evidence_root="${1:-runtime-characterization/target/evidence/v01-development-host}"
work_root="${2:-runtime-characterization/target/work/v01-development-host}"

rm -rf "${evidence_root}" "${work_root}"
mkdir -p "${evidence_root}" "${work_root}"

./mvnw --batch-mode --no-transfer-progress   install   -Pruntime-characterization   -pl runtime-characterization   -am

run_case() {
  local case_name="$1"
  local workload="$2"
  local repetition="$3"
  local preload="$4"

  local output="${evidence_root}/${case_name}-r${repetition}.json"
  local work_dir="${work_root}/${case_name}-r${repetition}"

  args=(
    "--workload" "${workload}"
    "--warmup-count" "20"
    "--measured-count" "100"
    "--rate" "20"
    "--query-limit" "100"
    "--work-dir" "${work_dir}"
    "--output" "${output}"
  )

  if [[ "${preload}" != "0" ]]; then
    args+=("--preload" "${preload}")
  fi

  printf 'Running V01 case %s repetition %s\n' "${case_name}" "${repetition}"

  ./mvnw --batch-mode --no-transfer-progress     -Pruntime-characterization     -pl runtime-characterization     exec:java     -Dexec.args="${args[*]}"

  test -s "${output}"
  grep -F '"outcome" : "PASS"' "${output}"
}

for repetition in 1 2 3; do
  run_case "steady" "steady" "${repetition}" "0"
  run_case "burst" "burst" "${repetition}" "0"
  run_case "history-1000" "history" "${repetition}" "1000"
  run_case "history-9999" "history" "${repetition}" "9999"
done

count="$(find "${evidence_root}" -maxdepth 1 -type f -name '*.json' | wc -l | tr -d ' ')"
if [[ "${count}" != "12" ]]; then
  echo "Expected 12 V01 evidence files, found ${count}" >&2
  exit 1
fi

printf 'V01 baseline evidence written to %s\n' "${evidence_root}"
