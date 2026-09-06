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

# ── 1b. Zero files: cache hit or genuine breakage? ──────────────────────────
# NO EMPTY REPORT IS REPORTED GREEN — an empty page looks like "all fine" (same reasoning
# as the browser guard in playwright.yml). But "no XML" has two very different causes, and
# turning red for the harmless one would make this step noise within a week:
#
#   (a) THE BUILD CACHE RESTORED EVERYTHING. A pull request that touches no Java file
#       rebuilds no module, so no test runs and no report can exist. Measured on
#       31.08.2026 in pipeline 50 of this very branch: `mvn install` green in three
#       minutes, `*/target/surefire-reports/` absent entirely.
#       `.mvn/maven-build-cache-config.xml` now carries `attachedOutputs`, so a restored
#       module brings its XML with it — but only for entries written AFTER that change.
#       Until the cache has turned over, case (a) still happens, and it is not an error.
#   (b) THE BUILD BROKE BEFORE THE FIRST TEST, or an `mvn clean` after the tests emptied
#       target/. That IS an error, and it must not hide behind case (a).
#
# The two are told apart by the cache extension's own report: it lists every module with
# <checksumMatched>. All matched = nothing was rebuilt.
if [ "$ANZAHL" -eq 0 ]; then
  BERICHT=$(find . -path "*/target/maven-incremental/cache-report*.xml" | head -1)
  MODULE=0; GETROFFEN=0
  if [ -n "$BERICHT" ]; then
    MODULE=$(grep -c "<project>" "$BERICHT" || true)
    GETROFFEN=$(grep -c "<checksumMatched>true</checksumMatched>" "$BERICHT" || true)
  fi
  if [ "$MODULE" -gt 0 ] && [ "$MODULE" -eq "$GETROFFEN" ]; then
    echo "No test report for this run: all $MODULE modules came out of the build cache,"
    echo "so no test was executed. That is not an error — see the last report at"
    echo "$REPORT_BASE_URL/$REPO/latest/ ."
    exit 0
  fi
  echo "ERROR: not a single JUnit XML found." >&2
  echo "       Searched: */target/surefire-reports/*.xml and */target/failsafe-reports/*.xml" >&2
  echo "       $GETROFFEN of $MODULE modules came from the build cache, so this is NOT the" >&2
  echo "       harmless 'nothing was rebuilt' case." >&2
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
  # `cd /tmp` is deliberate: run from the workspace, Maven would load the 24-module reactor
  # plus .mvn/extensions.xml and .mvn/maven.config just to copy one zip — slower, and every
  # way that reactor can fail would take the report down with it. Outside a project Maven
  # resolves the artifact straight into ~/.m2 and stops.
  ( cd /tmp && mvn -q -B org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy \
      -Dartifact="io.qameta.allure:allure-commandline:$ALLURE_VERSION:zip" \
      -DoutputDirectory=/tmp/allure-dl )
  mkdir -p /opt/allure
  # `jar xf` instead of `unzip`: the maven image is guaranteed to have a JDK, unzip not.
  (cd /opt/allure && jar xf "/tmp/allure-dl/allure-commandline-$ALLURE_VERSION.zip")
  # jar/zip does not carry the executable bit — without the chmod the next call aborts
  # with "Permission denied", which looks like a permission problem on the volume.
  chmod +x "$ALLURE_BIN"
fi
"$ALLURE_BIN" --version

# ── 4b. No phone-home to Google ─────────────────────────────────────────────
# Card 1042 (05.09.2026): every generated report ships a gtag script that reports to
# Google Analytics (G-FVWC4GKEYS) on every page view, WITH REFERRER — the referrer is the
# report URL under reports.plaintext.ch, which carries the repo name and the run number.
# `allure generate` itself ALSO calls google-analytics.com/mp/collect once, from the CI
# agent, during generation. One switch closes both, measured locally against
# allure-commandline 2.46.0 (465 XML in, one gtag hit without the switch):
#   - `allure.properties` with `allure.analytics.enabled=false` does NOT exist in 2.x — that
#     key belongs to the Allure 1.x legacy-XML reader (grepped allure-generator-2.46.0.jar:
#     the string appears only in Allure1Plugin.class). Left in place, it does nothing.
#   - `ALLURE_NO_ANALYTICS=1` does NOT work either: ReportWebGenerator reads the env var and
#     converts it with `Boolean.valueOf(...)`, which is only true for the literal string
#     "true" — "1" parses to false and the script stays in the report.
#   - `ALLURE_NO_ANALYTICS=true` works: 0 `googletagmanager` hits in the generated
#     index.html, and widgets/summary.json (the numbers) is unaffected.
export ALLURE_NO_ANALYTICS=true

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
BERICHT_URL="$REPORT_BASE_URL/$REPO/$LAUF/"
echo "════════════════════════════════════════════════════════════════"
echo " Test report: $BERICHT_URL"
echo " Always the latest: $REPORT_BASE_URL/$REPO/latest/"
echo " ${GESAMT:-?} test cases, ${DURCH:-?} green, ${ROT:-?} red."
echo "════════════════════════════════════════════════════════════════"

# ── 8. Put the link where people actually look: the PR check list ───────────
#  Card 1043 (Daniel, 02.09.2026: "is there a way to hand out the allure links in woodpecker
#  where there are any, and link them automatically?").
#
#  Woodpecker has no artefact tab. The usual route is a GitHub commit status of our own: the
#  report then sits as a line next to ci/woodpecker/pr/build, and its "Details" leads straight
#  to the report.
#
#  WITHOUT A TOKEN THIS STEP SAYS SO AND MOVES ON. A missing status must never turn a green
#  build red — the report is already written at this point, and losing it over a link would be
#  the wrong trade. The secret is `github_status_token` (Vault item github.claude-review-pat,
#  scope `repo`, which includes `repo:status`).
if [ -z "${GITHUB_STATUS_TOKEN:-}" ]; then
  echo "No GITHUB_STATUS_TOKEN — skipping the PR link. Set the woodpecker secret"
  echo "github_status_token on this repo to get the report as a check line."
  exit 0
fi

#  DER PR-HEAD STEHT SCHON IN CI_COMMIT_SHA (Karte 1058, 05.09.2026 — gemessen, nicht geglaubt).
#
#  Hier stand die Annahme: "bei pull_request baut Woodpecker den MERGE-Commit, ein Status darauf
#  erscheint in der PR-Liste nicht" — deshalb der Umweg ueber GET /pulls/<nr>. Fuer diese
#  Woodpecker-Fassung stimmt das nicht. Aus der API des Laufs 140 von plaintext-guild:
#
#      commit: 79f3644da45be69ab484e9ddbd8591bcff10ec31
#      ref:    refs/pull/285/merge
#
#  Derselbe Wert ist bei GitHub der headRefOid des PR. Der ref ist der Merge-Ref, der
#  aufgezeichnete Commit aber der HEAD.
#
#  WARUM DAS HIER GEAENDERT WIRD, OBWOHL ES IN root FUNKTIONIERT HAT: root ist oeffentlich, und
#  auf einen oeffentlichen PR antwortet GitHub auch ohne Recht. Bei den PRIVATEN Repos guild und
#  app antwortet dieselbe Anfrage mit 404 (am 05.09.2026 mit dem hinterlegten Token gemessen:
#  root 200, guild 404, app 404) — und weil `set -e` bei einer fehlgeschlagenen Zuweisung sofort
#  abbricht, starb der ganze Schritt STUMM mit exit 1, obwohl der Bericht laengst geschrieben war.
#  Die Pruefung `if [ -z "$SHA" ]` darunter war unerreichbar. Der Umweg war also nicht nur
#  unnoetig, er hat einen gruenen Bau rot gemacht.
SHA="${CI_COMMIT_SHA:-}"
#  Nur eine 40-stellige Hex-Zahl geht weiter. Alles andere waere ein 422, dessen Ursache
#  hinterher wie ein Tokenproblem aussieht.
case "$SHA" in
  *[!0-9a-f]* | "") SHA="" ;;
esac
[ "${#SHA}" -eq 40 ] || SHA=""

if [ -z "$SHA" ]; then
  echo "Commit-SHA nicht brauchbar — keine PR-Zeile gesetzt."
  echo "Der Bericht selbst ist geschrieben und erreichbar."
  exit 0
fi
echo "PR head commit: $SHA"

#  `success` even for a red test run: the status describes whether a REPORT exists, not whether
#  the tests passed. That is what ci/woodpecker/pr/build is for. After a red run the link
#  matters most, and a `failure` here would be a second red line saying nothing new.
BESCHREIBUNG="${GESAMT:-?} tests, ${ROT:-0} red"
ANTWORT=$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $GITHUB_STATUS_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  "https://api.github.com/repos/$CI_REPO/statuses/$SHA" \
  -d "{\"state\":\"success\",\"context\":\"Testbericht (Allure)\",\"target_url\":\"$BERICHT_URL\",\"description\":\"$BESCHREIBUNG\"}" || echo "000")

if [ "$ANTWORT" = "201" ]; then
  echo "PR check line posted on $SHA -> $BERICHT_URL"
else
  #  Deliberately not an error: see above. The HTTP code is enough to tell a wrong token (401)
  #  from a missing scope (403) from a wrong SHA (422).
  echo "Status not posted (HTTP $ANTWORT) — the report itself is written and reachable."
fi
