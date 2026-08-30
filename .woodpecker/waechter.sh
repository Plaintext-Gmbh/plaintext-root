#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
#  CI-Motor-Waechter fuer Woodpecker
#
#  Wird von JEDEM Woodpecker-Step als ERSTES Kommando GESOURCT:
#      - .woodpecker/waechter.sh
#  (der fuehrende Punkt ist Absicht: das `exit 0` unten beendet dann den Step
#  selbst — mit Erfolg, aber ohne irgendetwas getan zu haben.)
#
#  Er liest `.ci-engine` im Repo-Root. Genau ein Wort:
#      github      -> GitHub Actions faehrt dieses Repo, Woodpecker steigt aus
#      woodpecker  -> Woodpecker faehrt, die Steps laufen durch
#  Fehlt die Datei, gilt `github` — ein Repo ohne Datei aendert sich damit nicht.
#
#  WARUM EIN SOURCE UND KEIN `when:`-FILTER: Woodpecker kann `when` nur gegen
#  Ereignis, Branch, Pfad und Umgebungsvariablen auswerten, nicht gegen den
#  INHALT einer Datei im Repo. Eine Repo-Variable in der Woodpecker-Oberflaeche
#  koennte das — dann stuende der Umschalter aber in einer Datenbank auf dem NAS
#  und nicht im Git, und genau das soll er nicht: das Umschalten ist ein Commit,
#  den man im Log wiederfindet und zurueckdrehen kann.
#
#  WARUM `exit 0` UND NICHT `exit 1`: nicht zustaendig ist kein Fehler. Ein roter
#  Lauf je Push waere nach zwei Tagen Hintergrundrauschen, und ein rotes
#  Woodpecker-Symbol neben einem gruenen GitHub-Lauf ist genau die Verwirrung,
#  die der Umschalter vermeiden soll.
# ─────────────────────────────────────────────────────────────────────────────

if [ -f .ci-engine ]; then
    # tr -d: Zeilenumbruch und ein versehentliches CR aus einem Windows-Editor raus.
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
        echo " Bedienung:  docs/CI-UMSCHALTEN.md"
        echo "════════════════════════════════════════════════════════════════"
        exit 0
        ;;
    *)
        # HART ROT. Ein Tippfehler ('woodpecke', 'Woodpecker ') wuerde sonst BEIDE
        # Systeme stilllegen: der Waechter auf der GitHub-Seite liest dieselbe Datei
        # und steigt bei allem ausser 'github' aus. Ein Repo, in dem nichts mehr
        # deployt und trotzdem alles gruen ist, ist der teuerste aller Zustaende —
        # deshalb wird hier abgebrochen statt geraten.
        echo "FEHLER: .ci-engine enthaelt '${CI_MOTOR}' — weder 'github' noch 'woodpecker'." >&2
        echo "        Die Datei enthaelt genau EIN Wort. Solange sie falsch ist," >&2
        echo "        deployt WEDER GitHub NOCH Woodpecker." >&2
        exit 1
        ;;
esac
