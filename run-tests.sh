#!/usr/bin/env bash
# run-tests.sh — tag-profile entry points for the booking-enrichment test suite.
#
# Environment profile: SUITE_ENV=local (default), ci or external. Chooses which
# config/test-<env>.properties the suite reads, and with it whether the suite
# starts the service itself (local, ci) or runs against one somebody else
# deployed (external). README.md, "External profile configuration", lists every
# value the external profile reads.
#
# See TEST_SUITE_SPEC.md section 9.3 and section 6.3 for the tag expressions
# each profile maps to, and docs/TAGGING_GUIDELINE.md for the tagging rules
# that make these filters meaningful.
#
# Usage: ./run-tests.sh <preflight|smoke|functional|contract|full|nightly> [mvn args...]
#
# Anything after the profile is passed through to Maven, which is how a run is
# pointed at one particular deployment without editing a properties file:
#   SUITE_ENV=external ./run-tests.sh functional -Dkafka.bootstrap.servers=host:9092
set -euo pipefail

usage() {
  cat >&2 <<EOF
Usage: $0 <profile> [mvn args...]

Profiles:
  preflight    no scenarios; checks the environment is there — see below
  smoke        @smoke                              — budget: 60s  (section 9.4)
  functional   @functional and @critical            — fast, every-commit slice
  contract     @contract                            — schema + compatibility checks
  full         not @nightly                         — everything except the nightly volume suite
  nightly      @nightly                             — volume/slow suite, unbounded budget

preflight reports broker reachability, whether the three configured topics
exist and whether readiness answers 200, printing the value it used for each,
and exits non-zero if any of them is missing. Run it before pointing the suite
at a deployment you did not start: without it a missing topic surfaces as
scenario after scenario timing out on a message that was never going to
arrive, which reads as a service defect.

Examples:
  SUITE_ENV=external $0 preflight
  SUITE_ENV=external $0 functional -Dkafka.bootstrap.servers=host:9092
  $0 smoke
  $0 functional
  $0 contract
  $0 full
  $0 nightly
EOF
  exit 1
}

if [[ $# -lt 1 ]]; then
  usage
fi

PROFILE="$1"
shift

case "$PROFILE" in
  preflight)
    # test-compile, not test: the preflight must not run the suite it is there to
    # decide about. Exec on the test classpath, which is where the
    # config/test-<env>.properties it reads lives.
    mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
      -Dexec.mainClass=com.cozentus.enrichment.tests.harness.Preflight \
      -Dexec.classpathScope=test \
      -Dsuite.env="${SUITE_ENV:-local}" "$@"
    ;;
  smoke)
    mvn test -Pkafka -Dsuite.env="${SUITE_ENV:-local}" -Dcucumber.filter.tags="@smoke" "$@"
    ;;
  functional)
    mvn test -Pkafka -Dsuite.env="${SUITE_ENV:-local}" -Dcucumber.filter.tags="@functional and @critical" "$@"
    ;;
  contract)
    mvn test -Pkafka -Dsuite.env="${SUITE_ENV:-local}" -Dcucumber.filter.tags="@contract" "$@"
    ;;
  full)
    mvn test -Pkafka -Dsuite.env="${SUITE_ENV:-local}" -Dcucumber.filter.tags="not @nightly" "$@"
    ;;
  nightly)
    mvn test -Pkafka -Dsuite.env="${SUITE_ENV:-local}" -Pnightly -Dcucumber.filter.tags="@nightly" "$@"
    ;;
  *)
    echo "Unknown profile: '$PROFILE'" >&2
    echo >&2
    usage
    ;;
esac
