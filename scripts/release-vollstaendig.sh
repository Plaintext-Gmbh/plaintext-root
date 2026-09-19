#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  release-vollstaendig.sh — meldet halbe Releases (Karte 1281)
#
#  WARUM ES DAS GIBT
#  -----------------
#  Der Release-Lauf laedt die Modul-Artefakte NACHEINANDER hoch (kein deployAtEnd,
#  siehe pom.xml). Bricht der Lauf mittendrin ab, liegt ein TEIL der Module im
#  Paket-Repo und der Rest nicht. Das sieht von aussen aus wie ein fertiges
#  Release: `plaintext-root-parent` ist da, also schlaegt Renovate die Version vor
#  und `mvn` findet sie — bis jemand das fehlende Modul braucht.
#
#  Gemessen am 19.09.2026 (Karte 1281), Woodpecker-Repo 6:
#    1.698.0  Lauf 228, `killed` bei Reactor-Modul  5/26 ->  9 von 51 Dateien oben
#    1.700.0  Lauf 234, `killed` bei Reactor-Modul 25/26 -> 47 von 51 Dateien oben
#  Beide Laeufe wurden von Woodpecker abgeschossen, weil der naechste master-Push
#  eintraf: 228 starb 23 s nach dem Anlegen von 230, 234 starb 25 s nach dem Anlegen
#  von 236. Ursache ist die Repo-Einstellung `cancel_previous_pipeline_events`,
#  die `push` enthielt — root released auf JEDEN master-Push.
#
#  AUFRUF
#  ------
#    scripts/release-vollstaendig.sh <version>
#    scripts/release-vollstaendig.sh              # = letzter Tag (git describe)
#    scripts/release-vollstaendig.sh --letzte N   # die N juengsten Release-Tags
#
#  Exit 0  alle Artefakte liegen im Repo
#  Exit 1  mindestens eines fehlt  (die fehlenden werden namentlich aufgezaehlt)
#  Exit 2  die Version gibt es gar nicht (kein einziges Artefakt) — das ist KEIN
#          halbes Release, sondern eine saubere Abwesenheit
#
#  UMGEBUNG
#  --------
#    REPOSILITE_BASIS   Standard https://maven.plaintext.ch/releases
#    PRUEFE_GITHUB=1    prueft zusaetzlich GitHub Packages (braucht GITHUB_TOKEN)
#    MINDESTALTER_S     Standard 1800. Tags, die juenger sind, werden im Modus
#                       --letzte uebersprungen: ihr Release laeuft moeglicherweise
#                       noch und waere sonst ein Fehlalarm. Gemessen 19.09.2026:
#                       ein vollstaendiger root-Release-Lauf braucht vom Tag bis
#                       zum letzten Upload 14 min (1.699.0: 15:06:35 -> 15:20:51).
#
#  Die Modulliste kommt aus der pom.xml DES GEPRUEFTEN TAGS (`git show <tag>:pom.xml`),
#  nicht aus einer gepflegten Liste und nicht aus dem Arbeitsverzeichnis. Eine
#  gepflegte Liste veraltet beim naechsten neuen Modul; das Arbeitsverzeichnis
#  faerbt umgekehrt die Vergangenheit rot, sobald ein Modul dazukommt.
# ══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

REPOSILITE_BASIS="${REPOSILITE_BASIS:-https://maven.plaintext.ch/releases}"
GRUPPE_PFAD="ch/plaintext"
GITHUB_BASIS="https://maven.pkg.github.com/Plaintext-Gmbh/plaintext-mvn"

cd "$(dirname "$0")/.." || exit 1

MINDESTALTER_S="${MINDESTALTER_S:-1800}"

# ── Modus --letzte N: ruft sich selbst je Tag auf und sammelt die Befunde ──────
if [ "${1:-}" = "--letzte" ]; then
  ANZAHL="${2:-10}"
  TAGS="$(git tag --sort=-creatordate 2>/dev/null | grep -E '^[0-9]+\.[0-9]+\.[0-9]+$' | head -n "$ANZAHL")"
  [ -n "$TAGS" ] || { echo "release-vollstaendig: keine Release-Tags gefunden — uebersprungen."; exit 0; }
  JETZT="$(date -u +%s)"
  SCHLECHT=""
  for t in $TAGS; do
    tzeit="$(git log -1 --format=%ct "$t" 2>/dev/null || echo 0)"
    alter=$((JETZT - tzeit))
    if [ "$alter" -lt "$MINDESTALTER_S" ]; then
      echo "  $t  uebersprungen (${alter}s alt, Grenze ${MINDESTALTER_S}s — Release laeuft evtl. noch)"
      continue
    fi
    if "$0" "$t" > /tmp/rv.$$ 2>&1; then
      echo "  $t  vollstaendig"
    else
      rc=$?
      if [ "$rc" = "2" ]; then
        echo "  $t  nicht im Repo (kein halbes Release)"
      else
        echo "  $t  HALB"; SCHLECHT="$SCHLECHT $t"
        sed 's/^/      /' /tmp/rv.$$
      fi
    fi
    rm -f /tmp/rv.$$
  done
  if [ -n "$SCHLECHT" ]; then
    echo "HALBE RELEASES GEFUNDEN:$SCHLECHT"
    echo "Erst aufraeumen (Karte 1281), dann weiter releasen."
    echo "Notausgang, wenn es bewusst so bleiben soll: '[halbe-release-ok]' in die Commit-Zeile."
    exit 1
  fi
  echo "Alle geprueften Release-Tags sind vollstaendig."
  exit 0
fi

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  VERSION="$(git describe --tags --abbrev=0 2>/dev/null)"
fi
case "$VERSION" in
  ''|*SNAPSHOT*)
    echo "release-vollstaendig: keine Release-Version bestimmbar (bekommen: '${VERSION:-<leer>}') — uebersprungen."
    exit 0
    ;;
esac

# ── Modulliste — AUS DEM STAND DES TAGS, nicht aus dem Arbeitsverzeichnis ──────
#  Das ist keine Feinheit. Die erste Fassung las immer die aktuelle pom.xml und
#  meldete daraufhin 27 alte Releases (1.662.0 bis 1.688.0) als halb — sie kennen
#  `plaintext-root-watch` nicht, weil es das Modul damals noch nicht gab. Ein
#  Waechter, der bei jedem neuen Modul die gesamte Vergangenheit rot faerbt, wird
#  nach dem zweiten Fehlalarm abgeschaltet und prueft dann gar nichts mehr.
#  Also: liegt ein Tag mit dieser Version vor, kommt die Modulliste aus dem Tag.
if git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null 2>&1; then
  QUELLE="$VERSION"
  hole() { git show "$VERSION:$1" 2>/dev/null; }
else
  QUELLE="Arbeitsverzeichnis"
  hole() { cat "$1" 2>/dev/null; }
fi

WURZEL="$(hole pom.xml)"
[ -n "$WURZEL" ] || { echo "release-vollstaendig: pom.xml aus '$QUELLE' nicht lesbar." >&2; exit 1; }

PARENT_ID="$(printf '%s' "$WURZEL" | sed -n '/<\/parent>/,$p' | grep -m1 -oE '<artifactId>[^<]+' | sed 's/<artifactId>//')"
[ -n "$PARENT_ID" ] || { echo "release-vollstaendig: artifactId aus pom.xml nicht lesbar." >&2; exit 1; }

MODULE="$(printf '%s' "$WURZEL" | grep -oE '<module>[^<]+' | sed 's/<module>//')"
[ -n "$MODULE" ] || { echo "release-vollstaendig: keine <module>-Eintraege in pom.xml." >&2; exit 1; }

# artefakt|dateiendung  — pom-Module haben kein Jar
ZU_PRUEFEN="$(printf '%s|pom\n' "$PARENT_ID")"
for m in $MODULE; do
  MPOM="$(hole "$m/pom.xml")"
  [ -n "$MPOM" ] || { echo "release-vollstaendig: $m/pom.xml fehlt in '$QUELLE'." >&2; exit 1; }
  aid="$(printf '%s' "$MPOM" | sed -n '/<\/parent>/,$p' | grep -m1 -oE '<artifactId>[^<]+' | sed 's/<artifactId>//')"
  [ -n "$aid" ] || aid="$m"
  pack="$(printf '%s' "$MPOM" | grep -m1 -oE '<packaging>[^<]+' | sed 's/<packaging>//')"
  if [ "$pack" = "pom" ]; then
    ZU_PRUEFEN="$ZU_PRUEFEN
$aid|pom"
  else
    ZU_PRUEFEN="$ZU_PRUEFEN
$aid|pom
$aid|jar"
  fi
done

kopf() { printf '%s\n' "────────────────────────────────────────────────────────────────"; }

kopf
echo "Release-Vollstaendigkeit  Version $VERSION"
echo "Repo: $REPOSILITE_BASIS"
echo "Modulliste aus: $QUELLE"
kopf

DA=0; FEHLT=0; FEHLLISTE=""
while IFS='|' read -r aid endung; do
  [ -n "$aid" ] || continue
  url="$REPOSILITE_BASIS/$GRUPPE_PFAD/$aid/$VERSION/$aid-$VERSION.$endung"
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 --retry 2 --retry-delay 3 -I "$url")"
  if [ "$code" = "200" ]; then
    DA=$((DA + 1))
  else
    FEHLT=$((FEHLT + 1))
    FEHLLISTE="$FEHLLISTE  $code  $aid-$VERSION.$endung"$'\n'
  fi
done <<< "$ZU_PRUEFEN"

GESAMT=$((DA + FEHLT))
echo "Reposilite: $DA von $GESAMT Dateien da, $FEHLT fehlen."

# ── optional dasselbe gegen GitHub Packages ───────────────────────────────────
#  WICHTIG: nur 404 heisst "fehlt". maven.pkg.github.com antwortet auf HEAD und auf
#  ein Token ohne read:packages mit 401 — wer das als "fehlt" zaehlt, meldet ein
#  halbes Release, wo nur die Anmeldung fehlt (gemessen 19.09.2026: das gh-CLI-Token
#  liefert 401 auf ALLE 51 Dateien der vollstaendigen Version 1.701.0).
GH_FEHLT=0; GH_DA=0
if [ "${PRUEFE_GITHUB:-0}" = "1" ] && [ -n "${GITHUB_TOKEN:-}" ]; then
  # -L ist Pflicht: maven.pkg.github.com antwortet auf vorhandene Dateien mit 302
  # auf ein CDN. Ohne -L waere jede existierende Datei ein 302 und damit "unklar".
  probe="$(curl -sL -o /dev/null -w '%{http_code}' --max-time 30 \
           -H "Authorization: Bearer $GITHUB_TOKEN" \
           "$GITHUB_BASIS/$GRUPPE_PFAD/$PARENT_ID/maven-metadata.xml")"
  if [ "$probe" != "200" ]; then
    echo "GitHub Packages: NICHT BEURTEILBAR (Vorprobe auf maven-metadata.xml gab $probe)."
    echo "                 Token braucht read:packages. Kein Befund, keine Entwarnung."
  else
    GH_DA=0; GH_UNKLAR=0
    while IFS='|' read -r aid endung; do
      [ -n "$aid" ] || continue
      url="$GITHUB_BASIS/$GRUPPE_PFAD/$aid/$VERSION/$aid-$VERSION.$endung"
      code="$(curl -sL -o /dev/null -w '%{http_code}' --max-time 30 \
              -H "Authorization: Bearer $GITHUB_TOKEN" "$url")"
      case "$code" in
        200) GH_DA=$((GH_DA + 1)) ;;
        404) GH_FEHLT=$((GH_FEHLT + 1))
             FEHLLISTE="$FEHLLISTE  GH 404  $aid-$VERSION.$endung"$'\n' ;;
        *)   GH_UNKLAR=$((GH_UNKLAR + 1))
             echo "  GitHub $code bei $aid-$VERSION.$endung — weder da noch weg, nicht gewertet." ;;
      esac
    done <<< "$ZU_PRUEFEN"
    echo "GitHub Packages: $GH_DA von $GESAMT da, $GH_FEHLT fehlen, $GH_UNKLAR unklar."
  fi
elif [ "${PRUEFE_GITHUB:-0}" = "1" ]; then
  echo "GitHub Packages: uebersprungen (kein GITHUB_TOKEN) — keine Entwarnung."
fi

if [ "$FEHLT" -eq 0 ] && [ "$GH_FEHLT" -eq 0 ]; then
  echo "OK — Release $VERSION ist vollstaendig."
  exit 0
fi

# "Gar nicht da" gilt nur, wenn KEIN Repo etwas hat. Sonst verdeckt ein geraeumtes
# Reposilite ein halbes GitHub Packages — genau der Zustand am 19.09.2026 nach dem
# Aufraeumen: Reposilite 0 von 51, GitHub Packages 47 von 51. Das ist ein halbes
# Release, kein sauberes Fehlen.
if [ "$DA" -eq 0 ] && [ "$GH_DA" -eq 0 ]; then
  kopf
  echo "Version $VERSION liegt GAR NICHT im Repo ($GESAMT Dateien fehlen)."
  echo "Das ist kein halbes Release. mvn und Renovate sehen die Version nicht."
  kopf
  exit 2
fi

kopf
echo "HALBES RELEASE: $VERSION ist unvollstaendig."
printf '%s' "$FEHLLISTE"
kopf
cat <<'HINWEIS'
Gefahr: wer auf diese Version pinnt, baut gruen, bis er das fehlende Modul
braucht. Renovate schlaegt sie vor, weil das Parent-Artefakt vorhanden ist.

Zu tun:
  1. Pruefen, ob irgendein Repo schon darauf pinnt
     (pom.xml der Consumer nach der Versionsnummer durchsuchen).
  2. Pinnt niemand darauf: die Version aus BEIDEN Paket-Repos entfernen und die
     maven-metadata.xml der betroffenen Artefakte neu schreiben — eine Version,
     die in der Metadatei steht, wird weiter vorgeschlagen.
  3. Pinnt jemand darauf: nicht loeschen, sondern die fehlenden Module
     nachliefern.
Ursache und Vorgehen: Karte 1281.
HINWEIS
exit 1
