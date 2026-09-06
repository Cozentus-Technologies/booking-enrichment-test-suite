#!/usr/bin/env bash
# run-tests.sh — tag-profile entry points for the booking-enrichment test suite.
# See TEST_SUITE_SPEC.md section 9.3 and section 6.3 for the tag expressions
# each profile maps to, and docs/TAGGING_GUIDELINE.md for the tagging rules
# that make these filters meaningful.
#
# Usage: ./run-tests.sh <smoke|functional|contract|full|nightly>
set -euo pipefail

usage() {
  cat >&2 <<EOF
Usage: $0 <profile>

Profiles:
  smoke        @smoke                              — budget: 60s  (section 9.4)
  functional   @functional and @critical            — fast, every-commit slice
  contract     @contract                            — schema + compatibility checks
  full         not @nightly                         — everything except the nightly volume suite
  nightly      @nightly                             — volume/slow suite, unbounded budget

Examples:
  $0 smoke
  $0 functional
  $0 contract
  $0 full
  $0 nightly
EOF
  exit 1
}

if [[ $# -ne 1 ]]; then
  usage
fi

PROFILE="$1"

case "$PROFILE" in
  smoke)
    mvn test -Pkafka -Dcucumber.filter.tags="@smoke"
    ;;
  functional)
    mvn test -Pkafka -Dcucumber.filter.tags="@functional and @critical"
    ;;
  contract)
    mvn test -Pkafka -Dcucumber.filter.tags="@contract"
    ;;
  full)
    mvn test -Pkafka -Dcucumber.filter.tags="not @nightly"
    ;;
  nightly)
    mvn test -Pkafka -Pnightly -Dcucumber.filter.tags="@nightly"
    ;;
  *)
    echo "Unknown profile: '$PROFILE'" >&2
    echo >&2
    usage
    ;;
esac
