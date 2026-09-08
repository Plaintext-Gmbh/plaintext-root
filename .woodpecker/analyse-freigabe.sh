#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
#  Freigabe-Waechter fuer die woechentliche Voll-Analyse (.woodpecker/sonar.yml)
#
#  Wird von JEDEM Step dieses Workflows GESOURCT, direkt nach waechter.sh:
#      - . .woodpecker/waechter.sh
#      - . .woodpecker/analyse-freigabe.sh
#  Der fuehrende Punkt ist Absicht: das `exit 0` unten beendet dann den Step selbst —
#  mit Erfolg, aber ohne etwas getan zu haben. Dasselbe Muster wie waechter.sh.
#
#  WOZU: ein Cron-Lauf ist gewollt, ein versehentlicher Klick auf "Run pipeline" nicht.
#  Eine Voll-Analyse belegt rund eine Stunde lang EINEN VON ZWEI Slots des Agenten
#  (WOODPECKER_MAX_WORKFLOWS=2) — waehrend dieser Zeit bleibt genau ein Slot fuer
#  Deploys und PR-Builds. Auf der GitHub-Seite ist derselbe Fehler zweimal passiert:
#  am 17.08.2026 zwei unbeabsichtigte Blockaden von je zwei Stunden, ausgeloest durch
#  ein `workflow_dispatch` mit deploy-target=ci-only — dort haengt die Voll-Analyse am
#  EREIGNIS und nicht an einer Absicht, und der Kommentar in der Pipeline warnt seither
#  ausdruecklich davor.
#
#  Deshalb muss ein manueller Lauf sagen, dass er es ernst meint: im "Run pipeline"-
#  Dialog die Variable
#      analyse = voll
#  setzen. Es ist dieselbe Sicherung, die deploy.yml mit `deploy_target` hat.
#
#  WARUM `exit 0` UND KEIN `exit 1`: ein abgebrochener Step faerbt die Pipeline rot, und
#  rot loest den Pushover-Melder am Ende von sonar.yml aus — ein versehentlicher Klick
#  wuerde also eine Prioritaets-1-Meldung "Voll-Analyse fehlgeschlagen" erzeugen. Ein
#  Alarm, der regelmaessig nicht stimmt, gewoehnt dem Empfaenger das Hinsehen ab.
# ─────────────────────────────────────────────────────────────────────────────

if [ "$CI_PIPELINE_EVENT" = "manual" ] && [ "$analyse" != "voll" ]; then
    echo "════════════════════════════════════════════════════════════════"
    echo " AUSSTIEG: manueller Lauf ohne Ansage."
    echo " Die woechentliche Voll-Analyse (Sonar + OWASP-CVE + SpotBugs +"
    echo " Quality-Gate) laeuft rund eine Stunde und belegt dabei einen der"
    echo " beiden Slots des Agenten. Ein Klick soll das nicht aus Versehen tun."
    echo ""
    echo " Wirklich gewollt? Im 'Run pipeline'-Dialog die Variable setzen:"
    echo "     analyse = voll"
    echo ""
    echo " Der Step endet mit Erfolg, hat aber nichts getan."
    echo "════════════════════════════════════════════════════════════════"
    exit 0
fi

echo "Voll-Analyse freigegeben (Ereignis: $CI_PIPELINE_EVENT)."
