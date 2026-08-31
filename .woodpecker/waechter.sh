#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
#  CI engine guard for Woodpecker
#
#  SOURCED by EVERY Woodpecker step as its FIRST command:
#      - .woodpecker/waechter.sh
#  (the leading dot is deliberate: the `exit 0` below then ends the step
#  itself — successfully, but without having done anything.)
#
#  It reads `.ci-engine` in the repo root. Exactly one word:
#      github      -> GitHub Actions drives this repo, Woodpecker bows out
#      woodpecker  -> Woodpecker drives, the steps run through
#  If the file is missing, `github` applies — a repo without the file thus does not change.
#
#  WHY A SOURCE AND NOT A `when:` FILTER: Woodpecker can evaluate `when` only against
#  event, branch, path and environment variables, not against the
#  CONTENT of a file in the repo. A repo variable in the Woodpecker UI
#  could do it — but then the switch would sit in a database on the NAS
#  and not in git, and that is exactly what it must not do: switching is a commit
#  that can be found again in the log and turned back.
#
#  WHY `exit 0` AND NOT `exit 1`: not being responsible is not an error. A red
#  run per push would be background noise after two days, and a red
#  Woodpecker symbol next to a green GitHub run is precisely the confusion
#  the switch is meant to avoid.
# ─────────────────────────────────────────────────────────────────────────────

if [ -f .ci-engine ]; then
    # tr -d: strip the line break and an accidental CR from a Windows editor.
    CI_MOTOR="$(head -1 .ci-engine | tr -d '[:space:]')"
else
    CI_MOTOR=github
fi

case "$CI_MOTOR" in
    woodpecker)
        echo "CI-Motor: woodpecker — dieser Step ist zustaendig und laeuft."
        ;;
    github)
        echo "════════════════════════════════════════════════════════════════"
        echo " AUSSTIEG: .ci-engine sagt 'github'."
        echo " GitHub Actions faehrt dieses Repo. Woodpecker tut hier nichts —"
        echo " kein Build, kein Release, kein Deploy. Der Step endet mit Erfolg."
        echo " Umschalten: .ci-engine auf 'woodpecker' setzen."
        echo " Bedienung:  docs/CI.md"
        echo "════════════════════════════════════════════════════════════════"
        exit 0
        ;;
    *)
        # HARD RED. A typo ('woodpecke', 'Woodpecker ') would otherwise shut BOTH
        # systems down: the guard on the GitHub side reads the same file
        # and bows out on anything but 'github'. A repo in which nothing deploys
        # any more and everything is nevertheless green is the costliest of all states —
        # which is why this aborts instead of guessing.
        echo "FEHLER: .ci-engine enthaelt '${CI_MOTOR}' — weder 'github' noch 'woodpecker'." >&2
        echo "        Die Datei enthaelt genau EIN Wort. Solange sie falsch ist," >&2
        echo "        deployt WEDER GitHub NOCH Woodpecker." >&2
        exit 1
        ;;
esac
