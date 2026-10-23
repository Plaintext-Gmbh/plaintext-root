#!/usr/bin/env bash
# ══════════════════════════════════════════════════════════════════════════════
#  release-nachliefern.sh — liefert die fehlenden Dateien eines halben Releases
#  nach, DATEI fuer DATEI, und ohne eine schon vorhandene Datei je neu zu laden
#  (Karte 1563)
#
#  WARUM ES DAS GIBT
#  -----------------
#  Der Release-Lauf (`mvn clean deploy`, .woodpecker/deploy.yml) laedt je Modul
#  pom, jar, Pruefsummen und maven-metadata.xml nacheinander erst nach GitHub
#  Packages, dann in die Reposilite. Am 22.10.2026 blieb zweimal ein einzelner
#  PUT nach maven.pkg.github.com ohne Antwort — Lauf 634: die Pruefsumme
#  plaintext-root-watch-1.789.0.pom.sha1, Lauf 641: plaintext-admin-apitoken-
#  1.790.0.jar — und endete nach 46 Minuten mit "Read timed out". Beide Male
#  lag das Release danach halb in beiden Paket-Repos.
#
#  WARUM KEIN RETRY IM MAVEN-RESOLVER DAS HEILT
#  --------------------------------------------
#  Maven 3.10.0 (Image maven:3.10-eclipse-temurin-25) bringt Resolver 2.0.24
#  mit Apache HttpClient 4.5.14. Dessen Retry-Handler wiederholt einen PUT bei
#  Verbindungsabbruch, NIE aber bei einem Lese-Timeout (SocketTimeoutException
#  ist eine InterruptedIOException, und die steht auf der Ausschlussliste von
#  DefaultHttpRequestRetryHandler). Ein Retry auf Modulebene
#  (`retryFailedDeploymentCount`) laedt das ganze Modul neu — und die Reposilite
#  antwortet auf eine schon vorhandene Datei mit 409 (pom.xml, Release 1.636.0),
#  GitHub Packages ebenso (Lauf 641: die Jar, die "Read timed out" meldete, LAG
#  danach in GitHub Packages). Ein stumpfer Retry wird also selbst rot.
#
#  WAS DIESES SKRIPT TUT
#  ---------------------
#  Fuer jedes Modul der pom.xml und jedes Ziel (Reposilite, GitHub Packages):
#    1. Liegt das Modul fertig gebaut im lokalen Maven-Repository
#       (~/.m2/repository/ch/plaintext/<modul>/<version>/)? Wenn nein, ist es
#       "ungebaut" — der Reactor ist vor ihm gestorben. Das meldet das Skript in
#       der Zeile NACHLIEFERN_UNGEBAUT=... und ueberlaesst den Bau dem Aufrufer
#       (./build nachliefern: `mvn clean deploy -pl`), der dann den normalen
#       Deploy-Weg nimmt.
#    2. Fuer jede Datei (pom, jar, je .sha1 und .md5): erst HEAD. Nur was fehlt
#       (404), wird per PUT nachgeliefert. Vorhandenes wird nicht angefasst.
#    3. maven-metadata.xml des Artefakts: fehlt die Version darin, wird sie
#       ergaenzt und die Datei neu geschrieben (Metadaten DUERFEN ueberschrieben
#       werden — jedes Release tut das).
#
#  409 WIRD ABGEFANGEN, ABER NICHT VERSCHLUCKT: antwortet ein Ziel auf einen PUT
#  mit 409, prueft das Skript per HEAD nach. Ist die Datei jetzt da (sie kam
#  zwischen HEAD und PUT an, oder der haengende Upload des Vorlaufs ist doch noch
#  gelandet), zaehlt das als erledigt. Ist sie NICHT da, ist das ein echter
#  Fehler: das Ziel verweigert die Vervollstaendigung dieser Version, und das
#  Skript meldet es rot. Jede andere Antwort als 2xx ist ebenfalls ein Fehler.
#
#  AUFRUF
#  ------
#    scripts/release-nachliefern.sh <version>
#
#  Exit 0  alle gebauten Module liegen vollstaendig in allen Zielen, und es gibt
#          keine ungebauten Module
#  Exit 3  alle gebauten Module sind vollstaendig, aber Module fehlen noch ganz
#          (Zeile NACHLIEFERN_UNGEBAUT=<artifactIds>) — der Aufrufer baut sie
#  Exit 1  mindestens eine Datei liess sich nicht nachliefern (steht im Protokoll)
#  Exit 2  Aufrufsfehler (keine Version, kein Token, pom.xml nicht lesbar)
#
#  UMGEBUNG
#  --------
#    LOKALES_REPO      Standard ~/.m2/repository (Quelle der gebauten Dateien)
#    REPOSILITE_BASIS  Standard https://maven.plaintext.ch/releases
#    REPOSILITE_USER   Standard ci            + MAVEN_NAS_TOKEN  (settings.xml-Paar)
#    GITHUB_BASIS      Standard https://maven.pkg.github.com/Plaintext-Gmbh/plaintext-mvn
#    GITHUB_USER       Standard daniel-marthaler + MVN_DEPLOY_TOKEN
#    NACHLIEFERN_ZIELE Standard "reposilite github" — ein Ziel ohne Token wird
#                      mit Meldung uebersprungen und das Ergebnis ist dann KEIN
#                      Exit 0 (ein uebersprungenes Ziel ist nicht vollstaendig).
#    CURL_MAX_TIME     Standard 600 (Sekunden je PUT; ein haengender Upload
#                      kostet so hoechstens 10 Minuten, nicht 46)
#
#  Die Modulliste kommt wie in release-vollstaendig.sh aus der pom.xml des Tags.
# ══════════════════════════════════════════════════════════════════════════════
set -uo pipefail

VERSION="${1:-}"
case "$VERSION" in
  ''|*SNAPSHOT*|-*)
    echo "release-nachliefern: Release-Version fehlt oder ist ein SNAPSHOT ('${VERSION:-<leer>}')." >&2
    exit 2
    ;;
esac

LOKALES_REPO="${LOKALES_REPO:-$HOME/.m2/repository}"
REPOSILITE_BASIS="${REPOSILITE_BASIS:-https://maven.plaintext.ch/releases}"
REPOSILITE_USER="${REPOSILITE_USER:-ci}"
GITHUB_BASIS="${GITHUB_BASIS:-https://maven.pkg.github.com/Plaintext-Gmbh/plaintext-mvn}"
GITHUB_USER="${GITHUB_USER:-daniel-marthaler}"
NACHLIEFERN_ZIELE="${NACHLIEFERN_ZIELE:-reposilite github}"
CURL_MAX_TIME="${CURL_MAX_TIME:-600}"
GRUPPE_PFAD="ch/plaintext"

SELBST="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$SELBST")/.." || exit 2

for werkzeug in curl sha1sum md5sum; do
  command -v "$werkzeug" >/dev/null 2>&1 || { echo "release-nachliefern: '$werkzeug' fehlt." >&2; exit 2; }
done

# ── Modulliste aus dem Tag (Begruendung in release-vollstaendig.sh) ───────────
if git rev-parse -q --verify "refs/tags/$VERSION" >/dev/null 2>&1; then
  QUELLE="$VERSION"
  hole() { git show "$VERSION:$1" 2>/dev/null; }
else
  QUELLE="Arbeitsverzeichnis"
  hole() { cat "$1" 2>/dev/null; }
fi
WURZEL="$(hole pom.xml)"
[ -n "$WURZEL" ] || { echo "release-nachliefern: pom.xml aus '$QUELLE' nicht lesbar." >&2; exit 2; }
# Erste artifactId NACH dem </parent>-Block (auch dann, wenn beides auf einer Zeile steht);
# ohne <parent> die erste artifactId der Datei.
eigene_artifact_id() {
  local pom
  pom="$(cat)"
  if printf '%s' "$pom" | grep -q '</parent>'; then
    printf '%s' "$pom" | sed -n '/<\/parent>/,$p' | sed '1s/.*<\/parent>//' | grep -m1 -oE '<artifactId>[^<]+' | sed 's/<artifactId>//'
  else
    printf '%s' "$pom" | grep -m1 -oE '<artifactId>[^<]+' | sed 's/<artifactId>//'
  fi
}
PARENT_ID="$(printf '%s' "$WURZEL" | eigene_artifact_id)"
[ -n "$PARENT_ID" ] || { echo "release-nachliefern: artifactId aus pom.xml nicht lesbar." >&2; exit 2; }
MODULE="$(printf '%s' "$WURZEL" | grep -oE '<module>[^<]+' | sed 's/<module>//')"
[ -n "$MODULE" ] || { echo "release-nachliefern: keine <module>-Eintraege in pom.xml." >&2; exit 2; }

# artefakt|packaging — in Reactor-Reihenfolge der Deklaration; die Reihenfolge
# spielt hier keine Rolle, jedes Modul wird fuer sich beurteilt.
ARTEFAKTE="$(printf '%s|pom\n' "$PARENT_ID")"
for m in $MODULE; do
  MPOM="$(hole "$m/pom.xml")"
  [ -n "$MPOM" ] || { echo "release-nachliefern: $m/pom.xml fehlt in '$QUELLE'." >&2; exit 2; }
  aid="$(printf '%s' "$MPOM" | eigene_artifact_id)"
  [ -n "$aid" ] || aid="$m"
  pack="$(printf '%s' "$MPOM" | grep -m1 -oE '<packaging>[^<]+' | sed 's/<packaging>//')"
  [ "$pack" = "pom" ] || pack="jar"
  ARTEFAKTE="$ARTEFAKTE
$aid|$pack"
done

# ── Ziele ─────────────────────────────────────────────────────────────────────
# Je Ziel: Basis-URL, Benutzer, Token, Probe-Art. GitHub Packages antwortet auf
# vorhandene Dateien mit 302 auf ein CDN und verlangt bei JEDER Anfrage ein
# Token (auch lesend); die Reposilite ist lesend offen und kennt HEAD.
ziel_basis()  { case "$1" in reposilite) echo "$REPOSILITE_BASIS" ;; github) echo "$GITHUB_BASIS" ;; esac; }
ziel_user()   { case "$1" in reposilite) echo "$REPOSILITE_USER" ;;  github) echo "$GITHUB_USER" ;;  esac; }
ziel_token()  { case "$1" in reposilite) echo "${MAVEN_NAS_TOKEN:-}" ;; github) echo "${MVN_DEPLOY_TOKEN:-}" ;; esac; }

# probe <ziel> <url> -> HTTP-Code auf stdout. 200/206 = da, 404 = fehlt, Rest = unklar.
# GitHub: GET mit Range 0-0 und -L, weil HEAD dort nicht fuer jede Datei zuverlaessig
# ist (release-vollstaendig.sh, 19.09.2026) und ein voller GET die Jar laden wuerde.
probe() {
  local ziel="$1" url="$2"
  case "$ziel" in
    github)
      curl -sL -o /dev/null -w '%{http_code}' --max-time 60 --retry 2 --retry-delay 3 \
           -r 0-0 -H "Authorization: Bearer $(ziel_token "$ziel")" "$url"
      ;;
    *)
      curl -s -o /dev/null -w '%{http_code}' --max-time 60 --retry 2 --retry-delay 3 -I "$url"
      ;;
  esac
}

# lade_hoch <ziel> <datei> <url> -> 0 = Datei liegt jetzt dort, 1 = Fehler (gemeldet)
lade_hoch() {
  local ziel="$1" datei="$2" url="$3" code rc nachprobe
  code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 30 --max-time "$CURL_MAX_TIME" \
               -u "$(ziel_user "$ziel"):$(ziel_token "$ziel")" -T "$datei" "$url" 2>/dev/null)"
  rc=$?
  case "$code" in
    200|201|204)
      echo "    hochgeladen  $(basename "$url")"
      return 0
      ;;
    409)
      # Konflikt = "gibt es schon". Nachpruefen, nicht glauben.
      nachprobe="$(probe "$ziel" "$url")"
      case "$nachprobe" in
        200|206)
          echo "    409, aber vorhanden  $(basename "$url") — zaehlt als erledigt (kam zwischen Probe und PUT an)"
          return 0
          ;;
        *)
          echo "    FEHLER 409 ohne Datei  $(basename "$url") — das Ziel verweigert die Vervollstaendigung" >&2
          echo "           dieser Version (Nachprobe gab $nachprobe). Das heilt kein Retry; Version ist" >&2
          echo "           auf diesem Ziel zu loeschen oder das naechste Release zu nehmen." >&2
          return 1
          ;;
      esac
      ;;
    *)
      if [ "$rc" -ne 0 ] && { [ -z "$code" ] || [ "$code" = "000" ]; }; then
        # curl selbst ist gescheitert (Timeout, Verbindung weg). Der PUT KANN trotzdem
        # angekommen sein — Lauf 641 hat genau das gezeigt. Also nachpruefen.
        nachprobe="$(probe "$ziel" "$url")"
        case "$nachprobe" in
          200|206)
            echo "    Upload ohne Antwort (curl rc=$rc), Datei liegt aber da  $(basename "$url") — erledigt"
            return 0
            ;;
        esac
        echo "    FEHLER Upload abgebrochen (curl rc=$rc, Nachprobe $nachprobe)  $(basename "$url")" >&2
        return 1
      fi
      echo "    FEHLER HTTP $code beim PUT  $(basename "$url")" >&2
      return 1
      ;;
  esac
}

# pruefsumme_datei <datei> <sha1|md5> -> Pfad einer temporaeren Pruefsummendatei (nur der Hex-Wert,
# so schreibt sie auch der Resolver: Layout Maven2, Algorithmen SHA-1 und MD5).
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
pruefsumme_datei() {
  local datei="$1" art="$2" ziel
  ziel="$TMP/$(basename "$datei").$art"
  case "$art" in
    sha1) sha1sum "$datei" | cut -d' ' -f1 | tr -d '\n' > "$ziel" ;;
    md5)  md5sum  "$datei" | cut -d' ' -f1 | tr -d '\n' > "$ziel" ;;
  esac
  echo "$ziel"
}

# sichere_datei <ziel> <lokale datei> <url> — Datei plus .sha1/.md5, jeweils nur wenn sie fehlt.
# Rueckgabe 0 = alles da, 1 = etwas fehlt weiterhin.
sichere_datei() {
  local ziel="$1" datei="$2" url="$3" code rc=0 art
  code="$(probe "$ziel" "$url")"
  case "$code" in
    200|206) echo "    vorhanden    $(basename "$url")" ;;
    404)     lade_hoch "$ziel" "$datei" "$url" || rc=1 ;;
    *)       echo "    FEHLER Probe gab $code  $(basename "$url") — weder da noch weg, nicht angefasst" >&2; return 1 ;;
  esac
  [ "$rc" -eq 0 ] || return 1
  for art in sha1 md5; do
    code="$(probe "$ziel" "$url.$art")"
    case "$code" in
      200|206) ;;
      404)     lade_hoch "$ziel" "$(pruefsumme_datei "$datei" "$art")" "$url.$art" || rc=1 ;;
      *)       echo "    FEHLER Probe gab $code  $(basename "$url").$art" >&2; rc=1 ;;
    esac
  done
  return $rc
}

# sichere_metadaten <ziel> <artifactId> — maven-metadata.xml des Artefakts enthaelt die Version.
# Wird neu geschrieben, wenn die Version fehlt; die Datei ist in beiden Repos ueberschreibbar
# (jedes Release schreibt sie). Fehlt sie ganz, entsteht eine minimale.
sichere_metadaten() {
  local ziel="$1" aid="$2" url="$(ziel_basis "$ziel")/$GRUPPE_PFAD/$aid/maven-metadata.xml"
  local alt="$TMP/$aid-$ziel-metadata.xml" neu="$TMP/$aid-$ziel-metadata-neu.xml" code jetzt
  case "$ziel" in
    github) code="$(curl -sL -o "$alt" -w '%{http_code}' --max-time 60 --retry 2 --retry-delay 3 \
                    -H "Authorization: Bearer $(ziel_token "$ziel")" "$url")" ;;
    *)      code="$(curl -s  -o "$alt" -w '%{http_code}' --max-time 60 --retry 2 --retry-delay 3 "$url")" ;;
  esac
  jetzt="$(date -u +%Y%m%d%H%M%S)"
  case "$code" in
    200)
      if grep -q "<version>$VERSION</version>" "$alt"; then
        echo "    vorhanden    maven-metadata.xml (kennt $VERSION)"
        return 0
      fi
      # Version in <versions> ergaenzen, latest/release/lastUpdated nachziehen.
      sed -e "s#</versions>#  <version>$VERSION</version>\n    </versions>#" \
          -e "s#<latest>[^<]*</latest>#<latest>$VERSION</latest>#" \
          -e "s#<release>[^<]*</release>#<release>$VERSION</release>#" \
          -e "s#<lastUpdated>[^<]*</lastUpdated>#<lastUpdated>$jetzt</lastUpdated>#" "$alt" > "$neu"
      grep -q "<version>$VERSION</version>" "$neu" || {
        echo "    FEHLER maven-metadata.xml von $aid hat kein <versions>-Element — nicht angefasst" >&2
        return 1
      }
      ;;
    404)
      cat > "$neu" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>ch.plaintext</groupId>
  <artifactId>$aid</artifactId>
  <versioning>
    <latest>$VERSION</latest>
    <release>$VERSION</release>
    <versions>
      <version>$VERSION</version>
    </versions>
    <lastUpdated>$jetzt</lastUpdated>
  </versioning>
</metadata>
EOF
      ;;
    *)
      echo "    FEHLER maven-metadata.xml von $aid nicht lesbar (HTTP $code) — nicht angefasst" >&2
      return 1
      ;;
  esac
  local rc=0 art
  lade_hoch "$ziel" "$neu" "$url" || return 1
  for art in sha1 md5; do
    lade_hoch "$ziel" "$(pruefsumme_datei "$neu" "$art")" "$url.$art" || rc=1
  done
  return $rc
}

kopf() { printf '%s\n' "────────────────────────────────────────────────────────────────"; }
kopf
echo "Release-Nachlieferung  Version $VERSION"
echo "Quelle: $LOKALES_REPO/$GRUPPE_PFAD/<modul>/$VERSION  (Modulliste aus: $QUELLE)"
echo "Ziele:  $NACHLIEFERN_ZIELE"
kopf

UNGEBAUT=""
FEHLER=0
UEBERSPRUNGEN=0

# Ein Ziel ohne Token wird NICHT still weggelassen: das Ergebnis waere "vollstaendig"
# fuer ein Ziel, das nie angefasst wurde.
ZIELE_AKTIV=""
for ziel in $NACHLIEFERN_ZIELE; do
  case "$ziel" in
    reposilite|github) ;;
    *) echo "release-nachliefern: unbekanntes Ziel '$ziel' (erlaubt: reposilite github)." >&2; exit 2 ;;
  esac
  if [ -z "$(ziel_token "$ziel")" ]; then
    echo "Ziel $ziel: KEIN TOKEN in der Umgebung — uebersprungen, Ergebnis gilt nicht als vollstaendig."
    UEBERSPRUNGEN=1
    continue
  fi
  ZIELE_AKTIV="$ZIELE_AKTIV $ziel"
done

while IFS='|' read -r aid pack; do
  [ -n "$aid" ] || continue
  verz="$LOKALES_REPO/$GRUPPE_PFAD/$aid/$VERSION"
  pom="$verz/$aid-$VERSION.pom"
  jar="$verz/$aid-$VERSION.jar"
  if [ ! -f "$pom" ] || { [ "$pack" = "jar" ] && [ ! -f "$jar" ]; }; then
    echo "$aid: ungebaut (kein $aid-$VERSION.$pack in $verz)"
    UNGEBAUT="$UNGEBAUT $aid"
    continue
  fi
  for ziel in $ZIELE_AKTIV; do
    basis="$(ziel_basis "$ziel")/$GRUPPE_PFAD/$aid/$VERSION/$aid-$VERSION"
    echo "$aid -> $ziel"
    sichere_datei "$ziel" "$pom" "$basis.pom" || FEHLER=1
    if [ "$pack" = "jar" ]; then
      sichere_datei "$ziel" "$jar" "$basis.jar" || FEHLER=1
    fi
    sichere_metadaten "$ziel" "$aid" || FEHLER=1
  done
done <<< "$ARTEFAKTE"

kopf
UNGEBAUT="${UNGEBAUT# }"
echo "NACHLIEFERN_UNGEBAUT=$UNGEBAUT"
if [ "$FEHLER" -ne 0 ]; then
  echo "Nachlieferung UNVOLLSTAENDIG — mindestens eine Datei liess sich nicht nachliefern (siehe FEHLER oben)."
  exit 1
fi
if [ "$UEBERSPRUNGEN" -ne 0 ]; then
  echo "Nachlieferung nicht abschliessend: mindestens ein Ziel wurde ohne Token uebersprungen."
  exit 1
fi
if [ -n "$UNGEBAUT" ]; then
  echo "Alle gebauten Module sind vollstaendig; ungebaut (bauen und normal deployen): $UNGEBAUT"
  exit 3
fi
echo "OK — alle Module von $VERSION liegen vollstaendig in: $ZIELE_AKTIV"
exit 0
