#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION must be set}"
: "${APP_JAR:?APP_JAR must be set}"
: "${CORE_JAR:?CORE_JAR must be set}"
: "${TIMING_DATA_JAR:?TIMING_DATA_JAR must be set}"
: "${GITHUB_SHA:?GITHUB_SHA must be set}"

publication_dir="${PUBLICATION_DIR:-publication}"
system_test_evidence_dir="${SYSTEM_TEST_EVIDENCE_DIR:-system-test-evidence}"

test "$(cat "${publication_dir}/source-sha.txt")" = "${GITHUB_SHA}"
test -f "${publication_dir}/artifacts/${APP_JAR}"
test -f "${publication_dir}/artifacts/${CORE_JAR}"
test -f "${publication_dir}/artifacts/${TIMING_DATA_JAR}"

python3 tools/release_metadata.py verify-artifact   --jar "${publication_dir}/artifacts/${APP_JAR}"   --version "${RELEASE_VERSION}"   --revision "${GITHUB_SHA}"

python3 tools/release_metadata.py release-notes   --version "${RELEASE_VERSION}"   --output release-notes.md

(
  cd "${publication_dir}/artifacts"
  sha256sum "${TIMING_DATA_JAR}" "${CORE_JAR}" "${APP_JAR}"
) > "SHA256SUMS-${RELEASE_VERSION}.txt"

mkdir -p   "${publication_dir}/evidence/system-test/linux"   "${publication_dir}/evidence/system-test/windows"

cp -R "${system_test_evidence_dir}/linux/."   "${publication_dir}/evidence/system-test/linux/"
cp -R "${system_test_evidence_dir}/windows/."   "${publication_dir}/evidence/system-test/windows/"

tar -czf "event-timing-release-evidence-${RELEASE_VERSION}.tar.gz"   -C "${publication_dir}"   README.md source-sha.txt evidence orchestration
