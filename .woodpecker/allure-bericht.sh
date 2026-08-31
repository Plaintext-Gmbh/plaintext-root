#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
#  Allure test report, built from the JUnit XML that Surefire/Failsafe write anyway
#  Card 1018 (Daniel, 31.08.2026: "I would like to integrate Allure or similar into
#  Woodpecker so that one can see the tests. But only if that is possible at all.")
#
#  CALLED from a Woodpecker step, AFTER the tests and BEFORE any further `clean`:
#      sh .woodpecker/allure-bericht.sh
#
#  WHAT IT DOES: collects every target/surefire-reports/*.xml and
#  target/failsafe-reports/*.xml below the workspace, builds a finished HTML report
#  from them and puts it under $REPORT_ROOT/<repo>/<pipeline number>/. $REPORT_ROOT is
#  the volume `woodpecker-reports`, served by the nginx of
#  plaintext-dockercompose/tri/plaintext-reports.
#
#  WHY XML AND NOT THE .txt: the .txt files LIE about @Nested tests. Measured on
#  31.08.2026 in plaintext-root-menu-visibility:
#      ch.plaintext.menuesteuerung.web.MandateMenuBackingBeanTest.txt -> "Tests run: 0"
#      TEST-...MandateMenuBackingBeanTest.xml                         -> tests="31",
#                                                                        34 <testcase>
#  Today's failure step in playwright.yml dumps exactly those .txt files into the log —
#  whoever trusts them takes a class with 34 tests for empty. Allure counts the
#  <testcase> elements and reports 34. That repair is the real side benefit of this file.
#
#  WHY NO ALLURE ADAPTER IN THE POMS: Allure reads the common junit.xml dialects
#  directly. The adapter in the test code is only needed for steps, attachments and
#  labels INSIDE the tests. Proven on 31.08.2026 against real surefire-reports from
#  plaintext-root: 12 files in, 174 test cases in the report, and the sum of the
#  <testcase> elements is 174 as well. No change to Java code or POMs.
#
#  WHY NO EXTRA IMAGE: allure-commandline is published as a zip on Maven Central. This
#  step runs in the same `maven:3.9-eclipse-temurin-25` as the build, fetches the zip via
#  `dependency:copy` and thereby stores it in ~/.m2 — and ~/.m2 on the agent is the global
#  volume `woodpecker-m2`. So it is downloaded ONCE (30 MB) and never again. The
#  alternative would have been `frankescobar/allure-docker-service`: 398 MB, community
#  maintained, and containing a Flask server nobody here needs.
# ─────────────────────────────────────────────────────────────────────────────
set -eu

# ── Settings (all overridable from the step) ────────────────────────────────
ALLURE_VERSION="${ALLURE_VERSION:-2.46.0}"
REPORT_ROOT="${REPORT_ROOT:-/reports}"
# For the line in the log. Must match the publication in
# plaintext-dockercompose/tri/plaintext-reports/docker-compose.yaml.
REPORT_BASE_URL="${REPORT_BASE_URL:-http://192.168.1.224:1155}"
# Runs kept per repo. Proposal from card 1018; without a limit /volume1/docker grows
# silently, one report weighs 3-15 MB depending on the repo.
REPORT_KEEP="${REPORT_KEEP:-30}"

REPO="${CI_REPO_NAME:-unbekannt}"
LAUF="${CI_PIPELINE_NUMBER:-0}"
ZIEL="$REPORT_ROOT/$REPO/$LAUF"
SAMMEL=".allure-results"

echo "── Test report: $REPO, run $LAUF ──"

# ── 1. Collect the XML ──────────────────────────────────────────────────────
# Flat into ONE directory, with the module as a filename prefix: two modules may carry
# the same class name, and a `cp` without the prefix would silently overwrite one of
# them. The prefix only touches the file name, never the report — Allure takes the suite
# name from inside the XML.
rm -rf "$SAMMEL"
mkdir -p "$SAMMEL"

LISTE=$(mktemp)
find . -path "*/target/surefire-reports/*.xml" -o -path "*/target/failsafe-reports/*.xml" | sort > "$LISTE"

ANZAHL=0
# `< "$LISTE"` and NOT `find | while read`: inside a pipe the loop runs in a subshell and
# ANZAHL would be 0 again afterwards — the step would then report "no XML found" right
# after copying hundreds of them.
while IFS= read -r x; do
  [ -n "$x" ] || continue
  # ./plaintext-root-menu/target/surefire-reports/TEST-Foo.xml -> plaintext-root-menu
  MODUL=$(echo "$x" | sed 's|^\./||; s|/target/.*||; s|/|_|g')
  DATEI=$(basename "$x")
  cp "$x" "$SAMMEL/${MODUL}__${DATEI}"
  ANZAHL=$((ANZAHL + 1))
done < "$LISTE"
rm -f "$LISTE"

# NO EMPTY REPORT IS REPORTED GREEN. Same reasoning as the browser guard in
# playwright.yml: an empty page looks like "all fine". The most common real reason for
# zero files is a compile error BEFORE the first test — the build step is red already
# then, and these lines say why no numbers follow.
if [ "$ANZAHL" -eq 0 ]; then
  echo "ERROR: not a single JUnit XML found." >&2
  echo "       Searched: */target/surefire-reports/*.xml and */target/failsafe-reports/*.xml" >&2
  echo "       Most common cause: the build broke before the first test (compile error)." >&2
  echo '       Second most common: an "mvn clean" AFTER the tests emptied target/.' >&2
  exit 1
fi
echo "$ANZAHL XML file(s) collected."

# ── 2. Write the provenance into the report ─────────────────────────────────
# Allure shows environment.properties as a header. Without it every report looks like
# every other one, and with two open pull requests nobody can tell which is which.
cat > "$SAMMEL/environment.properties" <<EOF
Repo=$REPO
Branch=${CI_COMMIT_BRANCH:-?}
Commit=${CI_COMMIT_SHA:-?}
Event=${CI_PIPELINE_EVENT:-?}
Run=$LAUF
Workflow=${CI_WORKFLOW_NAME:-?}
EOF

# executor.json makes the points of the trend chart clickable — each one leads back to
# its Woodpecker run. That is the difference between "the trend dips" and "the trend
# dips, and here is the run".
cat > "$SAMMEL/executor.json" <<EOF
{
  "name": "Woodpecker",
  "type": "woodpecker",
  "url": "${CI_SYSTEM_URL:-https://ci.plaintext.ch}",
  "buildOrder": $LAUF,
  "buildName": "$REPO #$LAUF",
  "buildUrl": "${CI_PIPELINE_URL:-https://ci.plaintext.ch}",
  "reportUrl": "$REPORT_BASE_URL/$REPO/$LAUF/"
}
EOF

# ── 3. Carry over the history of the previous report ────────────────────────
# WITHOUT THIS STEP EVERY RUN SHOWS ONLY ITSELF. Allure writes its history into
# <report>/history/; for the next run to draw a trend, that directory has to be inside
# the results BEFORE `generate`. Proven on 31.08.2026: without the step
# widgets/history-trend.json holds exactly one point, with it two.
VORIG="$REPORT_ROOT/$REPO/latest/history"
if [ -d "$VORIG" ]; then
  cp -r "$VORIG" "$SAMMEL/history"
  echo "History of the previous run carried over — the report shows a trend."
else
  echo "No history present (first run) — the trend starts here."
fi

# ── 4. Obtain allure-commandline ────────────────────────────────────────────
# Through Maven, so that the zip lands in ~/.m2 and survives in the agent volume
# woodpecker-m2. The second run downloads nothing.
ALLURE_BIN="/opt/allure/allure-$ALLURE_VERSION/bin/allure"
if [ ! -x "$ALLURE_BIN" ]; then
  echo "Fetching allure-commandline $ALLURE_VERSION (from ~/.m2, otherwise Maven Central)…"
  mvn -q -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
      -Dartifact="io.qameta.allure:allure-commandline:$ALLURE_VERSION:zip" \
      -DoutputDirectory=/tmp/allure-dl
  mkdir -p /opt/allure
  # `jar xf` instead of `unzip`: the maven image is guaranteed to have a JDK, unzip not.
  (cd /opt/allure && jar xf "/tmp/allure-dl/allure-commandline-$ALLURE_VERSION.zip")
  # jar/zip does not carry the executable bit — without the chmod the next call aborts
  # with "Permission denied", which looks like a permission problem on the volume.
  chmod +x "$ALLURE_BIN"
fi
"$ALLURE_BIN" --version

# ── 5. Build the report and file it ─────────────────────────────────────────
mkdir -p "$REPORT_ROOT/$REPO"
rm -rf "$ZIEL"
"$ALLURE_BIN" generate "$SAMMEL" --clean -o "$ZIEL"

# `latest` is a symlink, not a second report: a copy would double the disk usage and be
# half overwritten during the next run.
ln -sfn "$LAUF" "$REPORT_ROOT/$REPO/latest"

# ── 6. Housekeeping ─────────────────────────────────────────────────────────
# Only purely numeric directories — `latest` is a symlink and falls through anyway, but a
# future `archiv/` must not be swept away with them.
ALT=$(ls -1 "$REPORT_ROOT/$REPO" 2>/dev/null | grep -E '^[0-9]+$' | sort -n | head -n "-$REPORT_KEEP" || true)
for a in $ALT; do
  echo "Housekeeping: old run $a removed (limit $REPORT_KEEP)."
  rm -rf "${REPORT_ROOT:?}/$REPO/$a"
done

# ── 7. The line this whole thing was built for ──────────────────────────────
GESAMT=$(grep -o '"total"[^,}]*' "$ZIEL/widgets/summary.json" | head -1 | tr -dc '0-9')
DURCH=$(grep -o '"passed"[^,}]*' "$ZIEL/widgets/summary.json" | head -1 | tr -dc '0-9')
ROT=$(grep -o '"failed"[^,}]*' "$ZIEL/widgets/summary.json" | head -1 | tr -dc '0-9')
echo "════════════════════════════════════════════════════════════════"
echo " Test report: $REPORT_BASE_URL/$REPO/$LAUF/"
echo " Always the latest: $REPORT_BASE_URL/$REPO/latest/"
echo " ${GESAMT:-?} test cases, ${DURCH:-?} green, ${ROT:-?} red."
echo " (reachable from the LAN / via Twingate only — card 1018, open question 1)"
echo "════════════════════════════════════════════════════════════════"
