#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# mobile-form-lint.sh — reporter (and optional auto-fixer) for mobile anti-patterns
# in JSF/PrimeFaces XHTML. Complements the central CSS fix (mobile-responsive.css) and the
# JUnit linter (MobileFormLinter) for the consumer rollout: it shows per file:line where dialogs
# / inputs / panelGrids overflow on a phone.
#
# Reported anti-patterns:
#   1. p:dialog with a fixed px width            (width="NNN" or width="NNNpx")
#   2. fixed px input width > 480px              (style="...width: 700px..." on fields)
#   3. h:panelGrid / p:panelGrid with >2 columns (columns="3"+)
#
# Usage:
#   scripts/mobile-form-lint.sh <repo-directory>            # report only (exit 1 on findings)
#   scripts/mobile-form-lint.sh --fix <repo-directory>      # p:dialog fixed width -> styleClass="mobile-safe"
#
# --fix is idempotent: dialogs that already carry styleClass="mobile-safe" / "mobile-exempt"
# are left untouched. The actual mobile effect comes from the central CSS; --fix mainly serves
# to convert many consumer dialogs quickly.

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

# Only real source XHTML; leave target/build directories out.
mapfile -t XHTML_FILES < <(find "$TARGET" -type f -name '*.xhtml' \
    -not -path '*/target/*' -not -path '*/build/*' -not -path '*/.git/*' | sort)

if [ "${#XHTML_FILES[@]}" -eq 0 ]; then
    echo "Keine .xhtml-Dateien unter '$TARGET' gefunden."
    exit 0
fi

# ---------------------------------------------------------------------------
# AWK reporter. Flattens multi-line p:dialog opening tags and reports
# anti-patterns as file:line.
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

    # --- collect the p:dialog opening tag (possibly spanning several lines) ---
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

    # --- fixed px input width > 480px inside style="..." ---
    # Only a real "width: NNNpx", NOT "max-width:"/"min-width:" (those are already fluid-friendly).
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

    # --- panelGrid with >2 columns ---
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
# --fix: p:dialog with a fixed width -> styleClass="mobile-safe" (idempotent).
# Replaces the width="NNN" attribute with styleClass="mobile-safe" when the dialog
# does not yet carry a mobile-* marker and has no styleClass.
# ---------------------------------------------------------------------------
if [ "$FIX" -eq 1 ]; then
    echo
    echo "== --fix: p:dialog fixe Breite -> styleClass=\"mobile-safe\" =="
    fixed_files=0
    for f in "${XHTML_FILES[@]}"; do
        # Perl slurp: for every p:dialog opening tag with a fixed width & without a
        # styleClass/mobile marker, replace width="NNN"/width="NNNpx" with styleClass="mobile-safe".
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

    # Re-check and count which files now have mobile-safe dialogs.
    for f in "${XHTML_FILES[@]}"; do
        if grep -q 'styleClass="mobile-safe"' "$f" 2>/dev/null; then
            fixed_files=$((fixed_files + 1))
        fi
    done
    echo "Dateien mit mobile-safe-Dialogen (nach --fix): $fixed_files"
    echo "Hinweis: die eigentliche Wirkung kommt aus dem zentralen mobile-responsive.css."
fi

# Exit code: 1 if there are findings (in pure report mode), otherwise 0.
if [ "$FIX" -eq 0 ] && [ "$report_rc" -ne 0 ]; then
    exit 1
fi
exit 0
