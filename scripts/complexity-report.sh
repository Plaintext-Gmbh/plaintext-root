#!/usr/bin/env bash
# Complexity Report: Analysiert Code-Komplexität und Struktur
set -uo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT=$(pwd)

echo "=== Plaintext-Root: Komplexitäts-Analyse ==="
echo "Datum: $(date '+%Y-%m-%d %H:%M')"
echo ""

# 1. Grösste Dateien (nach LOC)
echo "=== 1. Grösste Dateien (Top 15) ==="
printf "  %-6s %s\n" "LOC" "Datei"
printf "  %-6s %s\n" "───" "────"
find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null | while read -r file; do
    LOC=$(wc -l < "$file" | tr -d ' ')
    REL=$(echo "$file" | sed "s|$PROJECT_ROOT/||")
    echo "$LOC $REL"
done | sort -rn | head -15 | while read -r loc file; do
    printf "  %-6s %s\n" "$loc" "$file"
done

echo ""

# 2. Methoden pro Klasse (zu viele Methoden = God Class)
echo "=== 2. Klassen mit den meisten Methoden (Top 15) ==="
printf "  %-8s %s\n" "Methoden" "Klasse"
printf "  %-8s %s\n" "────────" "──────"
find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null | while read -r file; do
    METHOD_COUNT=$(grep -cE '(public|protected|private)\s+\w+\s+\w+\s*\(' "$file" 2>/dev/null || echo 0)
    REL=$(echo "$file" | sed "s|$PROJECT_ROOT/||")
    echo "$METHOD_COUNT $REL"
done | sort -rn | head -15 | while read -r count file; do
    [[ "$count" -gt 0 ]] && printf "  %-8s %s\n" "$count" "$file"
done

echo ""

# 3. Längste Methoden (Zeilen zwischen { und })
echo "=== 3. Lange Methoden (>50 Zeilen) ==="
LONG_METHODS=0
find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null | while read -r file; do
    REL=$(echo "$file" | sed "s|$PROJECT_ROOT/||")
    awk '
    /^\s*(public|protected|private)\s+.*\(/ { method_start=NR; method_name=$0; depth=0; in_method=1 }
    in_method && /{/ { depth++ }
    in_method && /}/ { depth--; if (depth<=0 && in_method) { len=NR-method_start; if (len>50) { gsub(/^\s+/,"",method_name); printf "  %4d Zeilen: %s (%s:%d)\n", len, method_name, "'"$REL"'", method_start }; in_method=0 } }
    ' "$file"
done | sort -t: -k1 -rn | head -15

echo ""

# 4. Zirkuläre Abhängigkeiten (einfache Heuristik: gegenseitige Imports)
echo "=== 4. Abhängigkeits-Analyse (Modul-Imports) ==="
for module_dir in "$PROJECT_ROOT"/plaintext-*/src/main/java; do
    [[ -d "$module_dir" ]] || continue
    MODULE=$(echo "$module_dir" | sed "s|$PROJECT_ROOT/||;s|/src/main/java||")
    IMPORTS=$(grep -rh '^import ch\.plaintext\.' "$module_dir" --include='*.java' 2>/dev/null \
        | sed 's/import ch\.plaintext\.\([a-z]*\)\..*/\1/' | sort -u | tr '\n' ', ' | sed 's/,$//')
    [[ -n "$IMPORTS" ]] && printf "  %-40s → %s\n" "$MODULE" "$IMPORTS"
done

echo ""

# 5. Modul-Grössen-Vergleich
echo "=== 5. Modul-Grössen ==="
printf "  %-42s %6s %6s %8s\n" "Modul" "Prod" "Test" "Ratio"
printf "  %-42s %6s %6s %8s\n" "─────" "────" "────" "─────"
for module_dir in "$PROJECT_ROOT"/plaintext-*/; do
    [[ -d "$module_dir" ]] || continue
    MODULE=$(basename "$module_dir")
    PROD=$(find "$module_dir/src/main/java" -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
    TEST=$(find "$module_dir/src/test/java" -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
    [[ "$PROD" -eq 0 ]] && continue
    if [[ "$TEST" -gt 0 ]]; then
        RATIO="$(( (TEST * 100) / PROD ))%"
    else
        RATIO="0% (!)"
    fi
    printf "  %-42s %6s %6s %8s\n" "$MODULE" "$PROD" "$TEST" "$RATIO"
done

echo ""

# 6. Zusammenfassung
echo "=== Zusammenfassung ==="
TOTAL_PROD=$(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
TOTAL_TEST=$(find "$PROJECT_ROOT"/plaintext-*/src/test/java -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
TOTAL_LOC=$(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null -exec cat {} + | wc -l | tr -d ' ')
AVG_LOC=$((TOTAL_LOC / TOTAL_PROD))
echo "  Produktions-Dateien: $TOTAL_PROD"
echo "  Test-Dateien:        $TOTAL_TEST"
echo "  Prod LOC gesamt:     $TOTAL_LOC"
echo "  Durchschn. LOC/File: $AVG_LOC"
echo "  Test-Abdeckung:      $(( (TOTAL_TEST * 100) / TOTAL_PROD ))% der Dateien haben Tests"
