#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# mobile-form-lint.sh — Reporter (und optionaler Auto-Fixer) fuer Mobile-Anti-Patterns
# in JSF/PrimeFaces-XHTML. Ergaenzt den zentralen CSS-Fix (mobile-responsive.css) und den
# JUnit-Linter (MobileFormLinter) fuers Consumer-Rollout: es zeigt pro Datei:Zeile, wo Dialoge
# / Inputs / PanelGrids auf dem Handy ueberlaufen.
#
# Gemeldete Anti-Patterns:
#   1. p:dialog mit fixer px-Breite            (width="NNN" oder width="NNNpx")
#   2. fixe px-Input-Breite > 480px            (style="...width: 700px..." an Feldern)
#   3. h:panelGrid / p:panelGrid mit >2 Spalten (columns="3"+)
#
# Verwendung:
#   scripts/mobile-form-lint.sh <repo-verzeichnis>          # nur Report (exit 1 bei Funden)
#   scripts/mobile-form-lint.sh --fix <repo-verzeichnis>    # p:dialog fixe Breite -> styleClass="mobile-safe"
#
# --fix ist idempotent: Dialoge, die bereits styleClass="mobile-safe" / "mobile-exempt" tragen,
# werden nicht angefasst. Die eigentliche Mobile-Wirkung kommt aus dem zentralen CSS; --fix dient
# v. a. dem schnellen Umstellen vieler Consumer-Dialoge.

set -euo pipefail

FIX=0
TARGET=""

usage() {
    grep '^#' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

for arg in "$@"; do
    case "$arg" in
        --fix) FIX=1 ;;
        -h|--help) usage 0 ;;
        -*) echo "Unbekannte Option: $arg" >&2; usage 1 ;;
        *) TARGET="$arg" ;;
    esac
done

if [ -z "$TARGET" ]; then
    echo "Fehler: kein Repo-Verzeichnis angegeben." >&2
    usage 1
fi
if [ ! -d "$TARGET" ]; then
    echo "Fehler: '$TARGET' ist kein Verzeichnis." >&2
    exit 1
fi

# Nur echte Quell-XHTML, target/build-Verzeichnisse ausklammern.
mapfile -t XHTML_FILES < <(find "$TARGET" -type f -name '*.xhtml' \
    -not -path '*/target/*' -not -path '*/build/*' -not -path '*/.git/*' | sort)

if [ "${#XHTML_FILES[@]}" -eq 0 ]; then
    echo "Keine .xhtml-Dateien unter '$TARGET' gefunden."
    exit 0
fi

# ---------------------------------------------------------------------------
# AWK-Reporter. Flacht mehrzeilige p:dialog-Oeffnungstags zusammen und meldet
# Anti-Patterns mit Datei:Zeile.
# ---------------------------------------------------------------------------
report_awk='
function flush_dialog(   flat) {
    if (in_dialog) {
        flat = dialog_buf
        gsub(/\n/, " ", flat)
        if (flat ~ /width[ ]*=[ ]*"[ ]*[0-9]+(px)?[ ]*"/ \
            && flat !~ /mobile-safe/ && flat !~ /mobile-exempt/ && flat !~ /mobile-ok/) {
            match(flat, /width[ ]*=[ ]*"[ ]*[0-9]+(px)?[ ]*"/)
            w = substr(flat, RSTART, RLENGTH)
            printf "%s:%d: [dialog-fixed-width] p:dialog mit fixer Breite (%s) -> laeuft auf dem Handy ueber\n", FILENAME, dialog_line, w
            findings++
        }
        in_dialog = 0
        dialog_buf = ""
    }
}
{
    line = $0

    # --- p:dialog Oeffnungstag (evtl. mehrzeilig) einsammeln ---
    if (!in_dialog && line ~ /<p:dialog([ \t>]|$)/) {
        in_dialog = 1
        dialog_line = FNR
        dialog_buf = line
        if (line ~ />/) flush_dialog()
        next
    }
    if (in_dialog) {
        dialog_buf = dialog_buf "\n" line
        if (line ~ />/) flush_dialog()
        next
    }

    # --- fixe px-Input-Breite > 480px in style="..." ---
    # Nur echtes "width: NNNpx", NICHT "max-width:"/"min-width:" (die sind bereits fluid-vertraeglich).
    tmp = line
    while (match(tmp, /width[ ]*:[ ]*[0-9]+px/)) {
        pre = (RSTART >= 4) ? substr(tmp, RSTART - 4, 4) : ""
        seg = substr(tmp, RSTART, RLENGTH)
        n = seg; gsub(/[^0-9]/, "", n)
        if (n + 0 > 480 && pre !~ /max-$/ && pre !~ /min-$/) {
            printf "%s:%d: [input-fixed-width] fixe px-Breite %spx (>480) -> auf schmalen Viewports zu breit\n", FILENAME, FNR, n
            findings++
        }
        tmp = substr(tmp, RSTART + RLENGTH)
    }

    # --- panelGrid mit >2 Spalten ---
    if (line ~ /panelGrid/ && match(line, /columns[ ]*=[ ]*"[0-9]+"/)) {
        seg = substr(line, RSTART, RLENGTH)
        cols = seg; gsub(/[^0-9]/, "", cols)
        if (cols + 0 > 2) {
            printf "%s:%d: [panelgrid-cols] panelGrid columns=%s (>2) -> bricht auf dem Handy schwer um\n", FILENAME, FNR, cols
            findings++
        }
    }
}
END { exit (findings > 0 ? 10 : 0) }
'

echo "== Mobile-Form-Lint: Report =="
echo "Verzeichnis: $TARGET   (Dateien: ${#XHTML_FILES[@]})"
echo

set +e
awk "$report_awk" "${XHTML_FILES[@]}"
report_rc=$?
set -e

if [ "$report_rc" -eq 0 ]; then
    echo "Keine Mobile-Anti-Patterns gefunden."
else
    echo
    echo "Anti-Patterns gefunden (siehe oben)."
fi

# ---------------------------------------------------------------------------
# --fix: p:dialog mit fixer Breite -> styleClass="mobile-safe" (idempotent).
# Ersetzt das width="NNN"-Attribut durch styleClass="mobile-safe", wenn der Dialog
# noch keinen mobile-* Marker traegt und keine styleClass hat.
# ---------------------------------------------------------------------------
if [ "$FIX" -eq 1 ]; then
    echo
    echo "== --fix: p:dialog fixe Breite -> styleClass=\"mobile-safe\" =="
    fixed_files=0
    for f in "${XHTML_FILES[@]}"; do
        # Perl-Slurp: pro p:dialog-Oeffnungstag mit fixer Breite & ohne styleClass/mobile-Marker
        # das width="NNN"/width="NNNpx" durch styleClass="mobile-safe" ersetzen.
        if perl -0777 -i -pe '
            my $count = 0;
            s{(<p:dialog\b[^>]*?>)}{
                my $tag = $1;
                if ($tag =~ /\bwidth\s*=\s*"\s*\d+(?:px)?\s*"/i
                    && $tag !~ /styleClass/i
                    && $tag !~ /mobile-(?:safe|exempt|ok)/i) {
                    $tag =~ s/\bwidth\s*=\s*"\s*\d+(?:px)?\s*"/styleClass="mobile-safe"/i;
                    $count++;
                }
                $tag;
            }gse;
            $main::CHANGED = 1 if $count;
            END { }
        ' "$f" 2>/dev/null; then
            :
        fi
    done

    # Re-check + Zaehlung, welche Dateien jetzt mobile-safe-Dialoge haben.
    for f in "${XHTML_FILES[@]}"; do
        if grep -q 'styleClass="mobile-safe"' "$f" 2>/dev/null; then
            fixed_files=$((fixed_files + 1))
        fi
    done
    echo "Dateien mit mobile-safe-Dialogen (nach --fix): $fixed_files"
    echo "Hinweis: die eigentliche Wirkung kommt aus dem zentralen mobile-responsive.css."
fi

# Exit-Code: 1 wenn (im reinen Report-Modus) Funde existieren, sonst 0.
if [ "$FIX" -eq 0 ] && [ "$report_rc" -ne 0 ]; then
    exit 1
fi
exit 0
