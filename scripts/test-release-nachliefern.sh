#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# scripts/test-release-nachliefern.sh — Selbsttest fuer scripts/release-nachliefern.sh (Karte 1596)
#
# Attrappen, kein Netz: ein HTTP-Server auf 127.0.0.1 spielt Reposilite (/repo) und GitHub
# Packages (/gh) und protokolliert Methode, Pfad und Authorization; ein curl im PATH schreibt
# seine eigene Kommandozeile mit und ruft dann das echte curl; ein mvn im PATH "baut" ein Modul,
# indem es seine Dateien ins lokale Repo legt. Faelle:
#   1. v1: groupId ch.plaintext -> Pfad ch/plaintext, Tag ohne Praefix
#   2. v2: groupId ch.plaintext.v2 -> ch/plaintext/v2, Tag v2-<v> mit TAG_PRAEFIX=v2-;
#      ohne Praefix faellt das Skript aufs Arbeitsverzeichnis (dort steht absichtlich ch.falsch)
#   3. zweiter Lauf laedt nichts neu
#   4. Modul fehlt lokal -> Exit 3, NACHLIEFERN_UNGEBAUT
#   5. --runden: Runde 1 meldet ungebaut, mvn -pl baut, Runde 2 gruen, Gegenprobe gerufen
#   6. kein Token in irgendeiner curl-Kommandozeile, aber jedes Ziel hat das Token bekommen
# Aufruf: scripts/test-release-nachliefern.sh   (SKRIPT=<pfad> prueft eine andere Fassung)
# Exit 0 = alle Pruefungen gruen.
set -u
SKRIPT="${SKRIPT:-$(cd "$(dirname "$0")" && pwd)/release-nachliefern.sh}"
T="$(mktemp -d)"
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; rm -rf "$T"' EXIT
ROT=0; GRUEN=0
ok()   { echo "  gruen: $*"; GRUEN=$((GRUEN + 1)); }
rot()  { echo "  ROT:   $*"; ROT=$((ROT + 1)); }
pruefe() { if eval "$2"; then ok "$1"; else rot "$1"; fi; }

# Absichtlich mit Zeichen, die curl-Konfiguration maskieren muss.
TOK_NAS='nas"geheim\x1596'
TOK_GH='gh-geheim"\\ende'

# ── Attrappe Server ──────────────────────────────────────────────────────────
mkdir -p "$T/srv"
cat > "$T/server.py" <<'PY'
import http.server, os, sys
WURZEL, PROTOKOLL, PORTDATEI = sys.argv[1], sys.argv[2], sys.argv[3]
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"  # sonst wartet curl bei jedem PUT 1 s auf "100 Continue"
    def log_message(self, *a): pass
    def _pfad(self): return os.path.join(WURZEL, self.path.lstrip("/"))
    def _log(self):
        with open(PROTOKOLL, "a") as f:
            f.write("%s %s %s\n" % (self.command, self.path, self.headers.get("Authorization", "-")))
    def _lesen(self, body):
        self._log(); p = self._pfad()
        if os.path.isfile(p):
            data = open(p, "rb").read()
            self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers()
            if body: self.wfile.write(data)
        else:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
    def do_HEAD(self): self._lesen(False)
    def do_GET(self): self._lesen(True)
    def do_PUT(self):
        self._log(); p = self._pfad()
        data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if os.path.isfile(p) and not p.endswith("maven-metadata.xml") and ".xml." not in p:
            self.send_response(409)
        else:
            os.makedirs(os.path.dirname(p), exist_ok=True); open(p, "wb").write(data); self.send_response(201)
        self.send_header("Content-Length", "0"); self.end_headers()
s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
open(PORTDATEI, "w").write(str(s.server_address[1]))
s.serve_forever()
PY
python3 -I "$T/server.py" "$T/srv" "$T/server.log" "$T/port" & SERVER_PID=$!
for _ in $(seq 50); do [ -s "$T/port" ] && break; sleep 0.1; done
PORT="$(cat "$T/port")"
[ -n "$PORT" ] || { echo "Attrappe-Server startet nicht"; exit 2; }

# ── Attrappen curl und mvn ───────────────────────────────────────────────────
ECHT_CURL="$(command -v curl)"
mkdir -p "$T/bin"
cat > "$T/bin/curl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$T/argv.log"
exec "$ECHT_CURL" "\$@"
EOF
cat > "$T/bin/mvn" <<'EOF'
#!/usr/bin/env bash
# Attrappe: legt fuer jedes Modul aus -pl :a,:b dessen pom/jar ins lokale Repo (LOKALES_REPO, GRUPPE_PFAD_TEST)
printf '%s\n' "$*" >> "$MVN_LOG"
while [ $# -gt 0 ]; do
  if [ "$1" = "-pl" ]; then
    for a in $(echo "$2" | tr ',' ' '); do
      a="${a#:}"; d="$LOKALES_REPO/$GRUPPE_PFAD_TEST/$a/$VERSION_TEST"; mkdir -p "$d"
      echo "pom $a" > "$d/$a-$VERSION_TEST.pom"; echo "jar $a" > "$d/$a-$VERSION_TEST.jar"
    done
    shift
  fi
  shift
done
EOF
chmod +x "$T/bin/curl" "$T/bin/mvn"

# ── Fixture-Repo: Wurzel + 1 Jar-Modul + 1 pom-Modul ─────────────────────────
# $1 Verzeichnis, $2 groupId, $3 Tag
fixture() {
  local d="$1" g="$2" tag="$3"
  mkdir -p "$d/scripts" "$d/mod-a" "$d/mod-b"
  cp "$SKRIPT" "$d/scripts/release-nachliefern.sh"
  printf '#!/usr/bin/env bash\necho "GEGENPROBE $* PRUEFE_GITHUB=${PRUEFE_GITHUB:-} TAG_PRAEFIX=${TAG_PRAEFIX:-}" >> "%s"\n' "$T/gegenprobe.log" \
    > "$d/scripts/release-vollstaendig.sh"
  cat > "$d/pom.xml" <<EOF
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
    </parent>
    <groupId>$g</groupId>
    <artifactId>wurzel</artifactId>
    <packaging>pom</packaging>
    <modules>
        <module>mod-a</module>
        <module>mod-b</module>
    </modules>
    <dependencies><dependency><groupId>org.example</groupId><artifactId>x</artifactId></dependency></dependencies>
</project>
EOF
  printf '<project><parent><groupId>%s</groupId><artifactId>wurzel</artifactId></parent><artifactId>mod-a</artifactId></project>\n' "$g" > "$d/mod-a/pom.xml"
  printf '<project><parent><groupId>%s</groupId><artifactId>wurzel</artifactId></parent><artifactId>mod-b</artifactId><packaging>pom</packaging></project>\n' "$g" > "$d/mod-b/pom.xml"
  git -C "$d" init -q && git -C "$d" add -A && git -C "$d" -c user.name=t -c user.email=t@t commit -qm init
  git -C "$d" tag "$tag"
  # Nach dem Tag: Arbeitsverzeichnis mit falscher Gruppe — das Skript muss die Tag-Fassung lesen.
  sed -i "s#<groupId>$g</groupId>#<groupId>ch.falsch</groupId>#" "$d/pom.xml"
}
# $1 lokales Repo, $2 Gruppenpfad, $3 Version, $4.. Module
lokal_bauen() {
  local r="$1" gp="$2" v="$3" a; shift 3
  for a in "$@"; do
    mkdir -p "$r/$gp/$a/$v"; echo "pom $a" > "$r/$gp/$a/$v/$a-$v.pom"
    [ "$a" = "wurzel" ] || [ "$a" = "mod-b" ] || echo "jar $a" > "$r/$gp/$a/$v/$a-$v.jar"
  done
}
lauf() {   # $1 Fixture, $2 Lokales Repo, Rest: Argumente; Umgebung TAG_PRAEFIX vom Aufrufer
  PATH="$T/bin:$PATH" LOKALES_REPO="$2" MAVEN_NAS_TOKEN="$TOK_NAS" MVN_DEPLOY_TOKEN="$TOK_GH" \
    REPOSILITE_BASIS="http://127.0.0.1:$PORT/repo" GITHUB_BASIS="http://127.0.0.1:$PORT/gh" \
    MVN_LOG="$T/mvn.log" bash "$1/scripts/release-nachliefern.sh" "${@:3}"
}
puts() { grep -c "^PUT $1" "$T/server.log" 2>/dev/null || true; }

echo "── 1. v1: ch.plaintext, Tag ohne Praefix ──"
fixture "$T/v1" ch.plaintext 1.0.0
lokal_bauen "$T/m2v1" ch/plaintext 1.0.0 wurzel mod-a mod-b
: > "$T/server.log"
TAG_PRAEFIX="" lauf "$T/v1" "$T/m2v1" 1.0.0 > "$T/l1.txt" 2>&1; rc=$?
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "Jar unter repo/ch/plaintext/mod-a/1.0.0" '[ -f "$T/srv/repo/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar" ]'
pruefe "Jar unter gh/ch/plaintext/mod-a/1.0.0" '[ -f "$T/srv/gh/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar" ]'
pruefe "Metadaten mit groupId ch.plaintext" 'grep -q "<groupId>ch.plaintext</groupId>" "$T/srv/repo/ch/plaintext/mod-a/maven-metadata.xml"'
pruefe "nichts unter ch/falsch (Tag gelesen, nicht das Arbeitsverzeichnis)" '[ ! -e "$T/srv/repo/ch/falsch" ]'
pruefe ".sha1 stimmt" '[ "$(cat "$T/srv/repo/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar.sha1")" = "$(sha1sum < "$T/m2v1/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar" | cut -d" " -f1)" ]'

echo "── 2. v2: ch.plaintext.v2, Tag v2-2.0.1 ──"
fixture "$T/v2" ch.plaintext.v2 v2-2.0.1
lokal_bauen "$T/m2v2" ch/plaintext/v2 2.0.1 wurzel mod-a mod-b
TAG_PRAEFIX="v2-" lauf "$T/v2" "$T/m2v2" 2.0.1 > "$T/l2.txt" 2>&1; rc=$?
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "Quelle ist Tag v2-2.0.1" 'grep -q "Modulliste aus: v2-2.0.1" "$T/l2.txt"'
pruefe "Jar unter repo/ch/plaintext/v2/mod-a/2.0.1" '[ -f "$T/srv/repo/ch/plaintext/v2/mod-a/2.0.1/mod-a-2.0.1.jar" ]'
pruefe "Jar unter gh/ch/plaintext/v2/mod-a/2.0.1" '[ -f "$T/srv/gh/ch/plaintext/v2/mod-a/2.0.1/mod-a-2.0.1.jar" ]'
pruefe "Metadaten mit groupId ch.plaintext.v2" 'grep -q "<groupId>ch.plaintext.v2</groupId>" "$T/srv/repo/ch/plaintext/v2/mod-a/maven-metadata.xml"'
pruefe "v2 hat nichts unter v1-Pfad ch/plaintext/mod-a/2.0.1 abgelegt" '[ ! -e "$T/srv/repo/ch/plaintext/mod-a/2.0.1" ]'
# Gegenprobe: ohne Praefix gibt es den Tag 2.0.1 nicht -> Arbeitsverzeichnis (ch.falsch)
TAG_PRAEFIX="" lauf "$T/v2" "$T/m2v2" 2.0.1 > "$T/l2b.txt" 2>&1
pruefe "ohne TAG_PRAEFIX: Arbeitsverzeichnis, Gruppe ch/falsch" 'grep -q "Modulliste aus: Arbeitsverzeichnis" "$T/l2b.txt" && grep -q "ch/falsch" "$T/l2b.txt"'

echo "── 3. zweiter Lauf laedt nichts neu ──"
vorher="$(puts /repo/ch/plaintext/v2/mod-a/2.0.1/)"
TAG_PRAEFIX="v2-" lauf "$T/v2" "$T/m2v2" 2.0.1 > "$T/l3.txt" 2>&1; rc=$?
nachher="$(puts /repo/ch/plaintext/v2/mod-a/2.0.1/)"
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "keine weiteren PUTs auf Versionsdateien ($vorher -> $nachher)" '[ "$vorher" = "$nachher" ]'
pruefe "Metadaten 'vorhanden'" 'grep -q "vorhanden    maven-metadata.xml" "$T/l3.txt"'

echo "── 4. Modul fehlt lokal -> Exit 3 ──"
fixture "$T/v2u" ch.plaintext.v2 v2-2.0.2
lokal_bauen "$T/m2v2u" ch/plaintext/v2 2.0.2 wurzel mod-b
TAG_PRAEFIX="v2-" lauf "$T/v2u" "$T/m2v2u" 2.0.2 > "$T/l4.txt" 2>&1; rc=$?
pruefe "Exit 3 (rc=$rc)" '[ "$rc" -eq 3 ]'
pruefe "NACHLIEFERN_UNGEBAUT=mod-a" 'grep -qx "NACHLIEFERN_UNGEBAUT=mod-a" "$T/l4.txt"'

echo "── 5. --runden: bauen, zweite Runde gruen, Gegenprobe ──"
: > "$T/gegenprobe.log"; : > "$T/mvn.log"
GRUPPE_PFAD_TEST=ch/plaintext/v2 VERSION_TEST=2.0.2 TAG_PRAEFIX="v2-" \
  lauf "$T/v2u" "$T/m2v2u" --runden 2.0.2 > "$T/l5.txt" 2>&1; rc=$?
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "mvn clean deploy -pl :mod-a" 'grep -q "clean deploy -pl :mod-a " "$T/mvn.log"'
pruefe "Runde 2 erreicht, keine Runde 3" 'grep -q "^Runde 2/3" "$T/l5.txt" && ! grep -q "^Runde 3/3" "$T/l5.txt"'
pruefe "Gegenprobe mit PRUEFE_GITHUB=1 und TAG_PRAEFIX=v2-" 'grep -qx "GEGENPROBE 2.0.2 PRUEFE_GITHUB=1 TAG_PRAEFIX=v2-" "$T/gegenprobe.log"'
pruefe "mod-a jetzt in beiden Zielen" '[ -f "$T/srv/repo/ch/plaintext/v2/mod-a/2.0.2/mod-a-2.0.2.jar" ] && [ -f "$T/srv/gh/ch/plaintext/v2/mod-a/2.0.2/mod-a-2.0.2.jar" ]'
# Gegenprobe rot -> --runden rot
printf '#!/usr/bin/env bash\nexit 1\n' > "$T/v2u/scripts/release-vollstaendig.sh"
TAG_PRAEFIX="v2-" lauf "$T/v2u" "$T/m2v2u" --runden 2.0.2 > "$T/l5b.txt" 2>&1; rc=$?
pruefe "rote Gegenprobe -> Exit 1 (rc=$rc)" '[ "$rc" -eq 1 ]'

echo "── 6. Tokens nie in der Kommandozeile, aber angekommen ──"
pruefe "curl-Attrappe hat Aufrufe protokolliert ($(wc -l < "$T/argv.log"))" '[ "$(wc -l < "$T/argv.log")" -gt 20 ]'
pruefe "NAS-Token in 0 Kommandozeilen" '[ "$(grep -cF "geheim" "$T/argv.log")" -eq 0 ]'
pruefe "kein -u / Authorization in argv" '! grep -qE "(^| )-u |Authorization" "$T/argv.log"'
b64() { printf '%s' "$1" | base64 -w0; }
pruefe "Reposilite-PUT trug Basic ci:<Token> (Maskierung korrekt)" 'grep -qF "PUT /repo/ch/plaintext/v2/mod-a/2.0.1/mod-a-2.0.1.jar Basic $(b64 "ci:$TOK_NAS")" "$T/server.log"'
pruefe "GitHub-PUT trug Basic daniel-marthaler:<Token>" 'grep -qF "PUT /gh/ch/plaintext/v2/mod-a/2.0.1/mod-a-2.0.1.jar Basic $(b64 "daniel-marthaler:$TOK_GH")" "$T/server.log"'
pruefe "GitHub-Probe trug Bearer <Token>" 'grep -qF "GET /gh/ch/plaintext/v2/mod-a/2.0.1/mod-a-2.0.1.jar Bearer $TOK_GH" "$T/server.log"'
pruefe "keine Zugangsdatei (*.put/*.lese) in /dev/shm uebrig" '[ -z "$(find /dev/shm -newer "$T/port" \( -name "*.put" -o -name "*.lese" \) 2>/dev/null)" ]'

echo "────────────────────────────────────────"
echo "test-release-nachliefern: $GRUEN gruen, $ROT rot"
[ "$ROT" -eq 0 ]
