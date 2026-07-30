#!/usr/bin/env bash
# Dead Code Check: Findet unbenutzte Klassen, Methoden und Imports
set -uo pipefail

cd "$(dirname "$0")/.."
PROJECT_ROOT=$(pwd)

echo "=== Plaintext-Root: Dead Code Analyse ==="
echo "Datum: $(date '+%Y-%m-%d %H:%M')"
echo ""

# 1. Unbenutzte Imports
echo "=== 1. Unbenutzte Imports ==="
UNUSED_IMPORTS=0
while IFS= read -r file; do
    while IFS= read -r line; do
        # Extrahiere Klassenname aus Import
        CLASS=$(echo "$line" | sed 's/import .*\.\([A-Z][A-Za-z0-9]*\);/\1/' | tr -d ' ')
        [[ -z "$CLASS" ]] && continue
        [[ "$CLASS" == "*" ]] && continue
        # Prüfe ob die Klasse im File verwendet wird (ausser in der Import-Zeile selbst)
        COUNT=$(grep -c "\b$CLASS\b" "$file" 2>/dev/null || echo 0)
        if [[ "$COUNT" -le 1 ]]; then
            echo "  $file: $line"
            UNUSED_IMPORTS=$((UNUSED_IMPORTS + 1))
        fi
    done < <(grep '^import ' "$file" | grep -v 'import static' | grep -v '\*;$')
done < <(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null)
echo "  Gesamt: $UNUSED_IMPORTS unbenutzte Imports"

echo ""

# 2. Leere Klassen (nur Klassen-Definition, kein Inhalt)
echo "=== 2. Leere/Minimale Klassen ==="
EMPTY_CLASSES=0
while IFS= read -r file; do
    LOC=$(grep -v '^\s*$' "$file" | grep -v '^\s*//' | grep -v '^\s*\*' | grep -v '^package ' | grep -v '^import ' | wc -l | tr -d ' ')
    if [[ "$LOC" -le 5 ]]; then
        CLASS=$(basename "$file" .java)
        echo "  $CLASS ($LOC Zeilen): $(echo "$file" | sed "s|$PROJECT_ROOT/||")"
        EMPTY_CLASSES=$((EMPTY_CLASSES + 1))
    fi
done < <(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null)
echo "  Gesamt: $EMPTY_CLASSES minimale Klassen"

echo ""

# 3. TODO/FIXME/HACK Kommentare
echo "=== 3. TODO/FIXME/HACK Kommentare ==="
TODO_COUNT=0
while IFS= read -r match; do
    REL=$(echo "$match" | sed "s|$PROJECT_ROOT/||")
    echo "  $REL"
    TODO_COUNT=$((TODO_COUNT + 1))
done < <(grep -rn 'TODO\|FIXME\|HACK\|XXX' "$PROJECT_ROOT"/plaintext-*/src/main/java --include='*.java' 2>/dev/null || true)
echo "  Gesamt: $TODO_COUNT Kommentare"

echo ""

# 4. Duplizierte String-Literale (potentielle Konstanten)
echo "=== 4. Häufige String-Literale (Kandidaten für Konstanten) ==="
grep -roh '"[^"]\{10,\}"' "$PROJECT_ROOT"/plaintext-*/src/main/java --include='*.java' 2>/dev/null \
    | sort | uniq -c | sort -rn | head -15 | while IFS= read -r line; do
    COUNT=$(echo "$line" | awk '{print $1}')
    [[ "$COUNT" -ge 3 ]] && echo "  ${line}"
done

echo ""

# 5. Zusammenfassung
echo "=== Zusammenfassung ==="
JAVA_FILES=$(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
TOTAL_LOC=$(find "$PROJECT_ROOT"/plaintext-*/src/main/java -name '*.java' 2>/dev/null -exec cat {} + | wc -l | tr -d ' ')
echo "  Java-Dateien (Prod):  $JAVA_FILES"
echo "  Lines of Code (Prod): $TOTAL_LOC"
echo "  Unbenutzte Imports:   $UNUSED_IMPORTS"
echo "  Minimale Klassen:     $EMPTY_CLASSES"
echo "  TODO/FIXME:           $TODO_COUNT"
