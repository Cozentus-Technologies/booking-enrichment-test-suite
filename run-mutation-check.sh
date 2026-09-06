#!/usr/bin/env bash
#
# C-3. Runs the whole mutation check and regenerates docs/MUTATION_CHECK.md.
#
#   bash run-mutation-check.sh                 baseline plus every mutation fixture
#   bash run-mutation-check.sh mut-silent ...   only the fixtures named
#
# Everything the check needs is in this repository. The enrichment service is
# never read: the suite is pointed at target/contract-stub.jar instead, which is
# the whole argument for the stub. Verify that by moving the service jar aside
# and running this again - nothing changes.
#
# Deliberately not `set -e`. A mutation run is supposed to fail the build; a
# non-zero exit from Maven is the result being measured, not an error.

set -uo pipefail

cd "$(dirname "$0")" || exit 1

RESULTS_DIR="target/mutation-check"
SAMPLE_DIR="target/stub-sample"
STUB_JAR="target/contract-stub.jar"
CUCUMBER_JSON="target/cucumber-reports/cucumber.json"
DOC="docs/MUTATION_CHECK.md"

# The suite reads the bulk sample from the service repository, which is exactly
# what this check has to work without. It is generated here instead, and its
# oracle is derived from the baseline fixture rather than written by hand.
SAMPLE_DATA="$SAMPLE_DIR/bookings-sample.jsonl"
SAMPLE_ORACLE="$SAMPLE_DATA.expected.json"

echo "==> building the contract stub"
mvn -q package -DskipTests || { echo "could not build $STUB_JAR"; exit 1; }
[ -f "$STUB_JAR" ] || { echo "$STUB_JAR was not produced"; exit 1; }

echo "==> generating the bulk sample and its oracle"
java -cp "$STUB_JAR" com.cozentus.enrichment.stub.SampleData "$SAMPLE_DIR" 200 42 || exit 1

mkdir -p "$RESULTS_DIR"

# One source for both the plan and the report, so the slice a fixture was run
# against and the slice its results are read against cannot drift apart.
PLAN=$(java -cp "$STUB_JAR" com.cozentus.enrichment.stub.MutationCheck plan) || exit 1

wanted=("$@")
ran=0

while IFS=$'\t' read -r fixture tags; do
  [ -n "$fixture" ] || continue

  if [ ${#wanted[@]} -gt 0 ]; then
    case " ${wanted[*]} " in
      *" $fixture "*) ;;
      *) continue ;;
    esac
  fi

  echo
  echo "==> $fixture"
  echo "    $tags"
  rm -f "$CUCUMBER_JSON"

  STUB_FIXTURE="$fixture" mvn test -Pkafka \
    -Dservice.jar.path="$STUB_JAR" \
    -Dsample.data.path="$SAMPLE_DATA" \
    -Dsample.oracle.path="$SAMPLE_ORACLE" \
    -Dcucumber.filter.tags="$tags" \
    > "$RESULTS_DIR/$fixture.log" 2>&1
  echo "    maven exit $? (a non-zero exit is expected for every fixture but the baseline)"

  if [ -f "$CUCUMBER_JSON" ]; then
    cp "$CUCUMBER_JSON" "$RESULTS_DIR/$fixture.json"
    ran=$((ran + 1))
  else
    echo "    NO CUCUMBER REPORT - see $RESULTS_DIR/$fixture.log"
  fi
done <<< "$PLAN"

echo
echo "==> $ran run(s) complete; writing $DOC"
java -cp "$STUB_JAR" com.cozentus.enrichment.stub.MutationCheck report "$RESULTS_DIR" "$DOC"
