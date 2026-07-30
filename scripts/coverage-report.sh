#!/usr/bin/env bash
# Coverage Report: Runs tests with JaCoCo and generates a summary
set -uo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT=$(pwd)

echo "=== Plaintext-Root: Test Coverage Report ==="
echo "Datum: $(date '+%Y-%m-%d %H:%M')"
echo ""

echo "Tests laufen (mit JaCoCo)..."
mvn test -Dmaven.test.failure.ignore=true -q 2>/dev/null

echo ""
echo "=== Coverage pro Modul ==="
printf "  %-42s %6s %12s\n" "Modul" "Cover" "Instructions"
printf "  %-42s %6s %12s\n" "─────" "─────" "────────────"

TOTAL_M=0; TOTAL_C=0; FAIL_MODULES=""

for csv in "$PROJECT_ROOT"/plaintext-*/target/site/jacoco/jacoco.csv; do
    [[ -f "$csv" ]] || continue
    MODULE=$(echo "$csv" | sed "s|$PROJECT_ROOT/||;s|/target/.*||")
    MISSED=$(tail -n +2 "$csv" | awk -F, '{sum+=$4} END {print sum+0}')
    COVERED=$(tail -n +2 "$csv" | awk -F, '{sum+=$5} END {print sum+0}')
    TOTAL=$((MISSED + COVERED))
    TOTAL_M=$((TOTAL_M + MISSED))
    TOTAL_C=$((TOTAL_C + COVERED))

    if [[ $TOTAL -gt 0 ]]; then
        PCT=$(( (COVERED * 100) / TOTAL ))
        INDICATOR=""
        [[ $PCT -lt 50 ]] && INDICATOR=" (!)"
        [[ $PCT -lt 30 ]] && INDICATOR=" (!!)"
        printf "  %-42s %4d%% %6d/%d%s\n" "$MODULE" "$PCT" "$COVERED" "$TOTAL" "$INDICATOR"
        [[ $PCT -lt 50 ]] && FAIL_MODULES="$FAIL_MODULES  - $MODULE ($PCT%)\n"
    fi
done

echo ""
TOTAL_ALL=$((TOTAL_M + TOTAL_C))
if [[ $TOTAL_ALL -gt 0 ]]; then
    GESAMT_PCT=$(( (TOTAL_C * 100) / TOTAL_ALL ))
    echo "=== Gesamt: ${GESAMT_PCT}% ($TOTAL_C/$TOTAL_ALL instructions) ==="
fi

# Test-Failures anzeigen
echo ""
echo "=== Test-Ergebnisse ==="
TOTAL_TESTS=0; TOTAL_FAIL=0; TOTAL_ERR=0; TOTAL_SKIP=0
for report in "$PROJECT_ROOT"/plaintext-*/target/surefire-reports/*.txt; do
    [[ -f "$report" ]] || continue
    while IFS= read -r line; do
        if [[ "$line" =~ Tests\ run:\ ([0-9]+),\ Failures:\ ([0-9]+),\ Errors:\ ([0-9]+),\ Skipped:\ ([0-9]+) ]]; then
            TOTAL_TESTS=$((TOTAL_TESTS + ${BASH_REMATCH[1]}))
            TOTAL_FAIL=$((TOTAL_FAIL + ${BASH_REMATCH[2]}))
            TOTAL_ERR=$((TOTAL_ERR + ${BASH_REMATCH[3]}))
            TOTAL_SKIP=$((TOTAL_SKIP + ${BASH_REMATCH[4]}))
        fi
    done < "$report"
done
echo "  Tests: $TOTAL_TESTS | Failures: $TOTAL_FAIL | Errors: $TOTAL_ERR | Skipped: $TOTAL_SKIP"

if [[ -n "$FAIL_MODULES" ]]; then
    echo ""
    echo "=== Module unter 50% Coverage ==="
    echo -e "$FAIL_MODULES"
fi

echo ""
echo "HTML-Reports: plaintext-*/target/site/jacoco/index.html"
