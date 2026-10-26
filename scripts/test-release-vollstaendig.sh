#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
#
# scripts/test-release-vollstaendig.sh — Selbsttest fuer scripts/release-vollstaendig.sh (Karte 1602)
#
# Attrappen, kein Netz: ein HTTP-Server auf 127.0.0.1 spielt Reposilite (/repo) und GitHub
# Packages (/gh) und protokolliert Methode, Pfad und Authorization; ein curl im PATH schreibt
# seine eigene Kommandozeile mit und ruft dann das echte curl. Faelle:
#   1. vollstaendig in beiden Repos -> Exit 0, "OK", GitHub 3 von 3
#   2. ein Jar fehlt nur in GitHub -> Exit 1, "GH 404"
#   3. --letzte 1 (Selbstaufruf erbt das Token ueber die Umgebung)
#   4. GITHUB_TOKEN nie in einer curl-Kommandozeile, aber beim Server angekommen; keine Reste in /dev/shm
#   5.-9. fail closed (Karte 1603): Vorprobe 404, --letzte mit Vorprobe 404, Reposilite weg,
#         GitHub-Vorprobe 404, PRUEFE_GITHUB=1 ohne Token -> nie Exit 0
# Die Wurzel-pom hat ein <parent> und ist >64 KiB, und ein grep im PATH liest bei -q nur die ersten
# 4 KiB: so endet grep -q vor dem Schreiber wie im CI-Image (Karte 1603), und das deterministisch.
# Aufruf: scripts/test-release-vollstaendig.sh   (SKRIPT=<pfad> prueft eine andere Fassung)
# Exit 0 = alle Pruefungen gruen.
set -u
SKRIPT="${SKRIPT:-$(cd "$(dirname "$0")" && pwd)/release-vollstaendig.sh}"
T="$(mktemp -d)"
SERVER_PID=""
trap '[ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; rm -rf "$T"' EXIT
ROT=0; GRUEN=0
ok()   { echo "  gruen: $*"; GRUEN=$((GRUEN + 1)); }
rot()  { echo "  ROT:   $*"; ROT=$((ROT + 1)); }
pruefe() { if eval "$2"; then ok "$1"; else rot "$1"; fi; }

# Absichtlich mit Zeichen, die curl-Konfiguration maskieren muss.
TOK_GH='gh-geheim"\\ende'

# ── Attrappe Server ──────────────────────────────────────────────────────────
cat > "$T/server.py" <<'PY'
import http.server, os, sys
WURZEL, PROTOKOLL, PORTDATEI = sys.argv[1], sys.argv[2], sys.argv[3]
class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def _lesen(self, body):
        with open(PROTOKOLL, "a") as f:
            f.write("%s %s %s\n" % (self.command, self.path, self.headers.get("Authorization", "-")))
        p = os.path.join(WURZEL, self.path.lstrip("/"))
        if os.path.isfile(p):
            data = open(p, "rb").read()
            self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers()
            if body: self.wfile.write(data)
        else:
            self.send_response(404); self.send_header("Content-Length", "0"); self.end_headers()
    def do_HEAD(self): self._lesen(False)
    def do_GET(self): self._lesen(True)
s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
open(PORTDATEI, "w").write(str(s.server_address[1]))
s.serve_forever()
PY
mkdir -p "$T/srv"
python3 -I "$T/server.py" "$T/srv" "$T/server.log" "$T/port" & SERVER_PID=$!
for _ in $(seq 50); do [ -s "$T/port" ] && break; sleep 0.1; done
PORT="$(cat "$T/port")"
[ -n "$PORT" ] || { echo "Attrappe-Server startet nicht"; exit 2; }

ECHT_CURL="$(command -v curl)"
mkdir -p "$T/bin"
cat > "$T/bin/curl" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$T/argv.log"
exec "$ECHT_CURL" "\$@"
EOF
chmod +x "$T/bin/curl"
ECHT_GREP="$(command -v grep)"
cat > "$T/bin/grep" <<EOF
#!/usr/bin/env bash
# -q ohne Datei: nur die ersten 4 KiB lesen und dann enden, wie GNU grep im CI-Image
case " \$* " in *" -q "*) [ -t 0 ] || exec "$ECHT_GREP" "\$@" < <(head -c 4096) ;; esac
exec "$ECHT_GREP" "\$@"
EOF
chmod +x "$T/bin/grep"

# ── Fixture: Wurzel (pom) + mod-a (jar), Tag 1.0.0, Tag-Zeit weit zurueck (fuer --letzte) ──
F="$T/repo"
mkdir -p "$F/scripts" "$F/mod-a"
cp "$SKRIPT" "$F/scripts/release-vollstaendig.sh" && chmod +x "$F/scripts/release-vollstaendig.sh"
{ printf '<project>\n  <parent>\n    <groupId>org.springframework.boot</groupId>\n    <artifactId>spring-boot-starter-parent</artifactId>\n  </parent>\n'
  printf '  <groupId>ch.plaintext</groupId>\n  <artifactId>wurzel</artifactId>\n  <packaging>pom</packaging>\n  <modules><module>mod-a</module></modules>\n'
  for _ in $(seq 1200); do printf '  <!-- Fuellung: groesser als ein Pipe-Puffer (64 KiB) -->\n'; done
  printf '</project>\n'; } > "$F/pom.xml"
printf '<project><parent><artifactId>wurzel</artifactId></parent><artifactId>mod-a</artifactId></project>\n' > "$F/mod-a/pom.xml"
git -C "$F" init -q && git -C "$F" add -A
GIT_COMMITTER_DATE="2020-01-01T00:00:00Z" git -C "$F" -c user.name=t -c user.email=t@t commit -qm init
git -C "$F" tag 1.0.0
for r in repo gh; do
  mkdir -p "$T/srv/$r/ch/plaintext/wurzel/1.0.0" "$T/srv/$r/ch/plaintext/mod-a/1.0.0"
  echo m > "$T/srv/$r/ch/plaintext/wurzel/maven-metadata.xml"
  echo p > "$T/srv/$r/ch/plaintext/wurzel/1.0.0/wurzel-1.0.0.pom"
  echo p > "$T/srv/$r/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.pom"
  echo j > "$T/srv/$r/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar"
done
lauf() {
  PATH="$T/bin:$PATH" PRUEFE_GITHUB=1 GITHUB_TOKEN="$TOK_GH" \
    REPOSILITE_BASIS="http://127.0.0.1:$PORT/repo" GITHUB_BASIS="http://127.0.0.1:$PORT/gh" \
    bash "$F/scripts/release-vollstaendig.sh" "$@"
}
: > "$T/argv.log"

echo "── 1. vollstaendig in beiden Repos ──"
lauf 1.0.0 > "$T/l1.txt" 2>&1; rc=$?
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "Reposilite 3 von 3" 'grep -q "Reposilite: 3 von 3 Dateien da, 0 fehlen" "$T/l1.txt"'
pruefe "GitHub Packages 3 von 3" 'grep -q "GitHub Packages: 3 von 3 da, 0 fehlen, 0 unklar" "$T/l1.txt"'
pruefe "OK-Zeile" 'grep -q "OK — Release 1.0.0 ist vollstaendig" "$T/l1.txt"'

echo "── 2. Jar fehlt nur in GitHub Packages ──"
mv "$T/srv/gh/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar" "$T/weg.jar"
lauf 1.0.0 > "$T/l2.txt" 2>&1; rc=$?
mv "$T/weg.jar" "$T/srv/gh/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar"
pruefe "Exit 1 (rc=$rc)" '[ "$rc" -eq 1 ]'
pruefe "GH 404 mod-a-1.0.0.jar gemeldet" 'grep -q "GH 404  mod-a-1.0.0.jar" "$T/l2.txt"'

echo "── 3. --letzte 1 ──"
lauf --letzte 1 > "$T/l3.txt" 2>&1; rc=$?
pruefe "Exit 0 (rc=$rc)" '[ "$rc" -eq 0 ]'
pruefe "1.0.0 vollstaendig" 'grep -q "1.0.0  vollstaendig" "$T/l3.txt"'

echo "── 4. Token nie in der Kommandozeile, aber angekommen ──"
pruefe "curl-Attrappe hat Aufrufe protokolliert ($(wc -l < "$T/argv.log"))" '[ "$(wc -l < "$T/argv.log")" -ge 15 ]'
pruefe "Token in 0 Kommandozeilen" '[ "$(grep -cF "geheim" "$T/argv.log")" -eq 0 ]'
pruefe "kein -H/Authorization in argv" '! grep -qE "(^| )-H |Authorization" "$T/argv.log"'
pruefe "GitHub-Probe trug Bearer <Token> (Maskierung korrekt)" 'grep -qF "GET /gh/ch/plaintext/mod-a/1.0.0/mod-a-1.0.0.jar Bearer $TOK_GH" "$T/server.log"'
pruefe "Reposilite bekam kein Token" '! grep -E "^HEAD /repo/" "$T/server.log" | grep -qv " -$"'
pruefe "keine Zugangsdatei in /dev/shm uebrig" '[ -z "$(find /dev/shm -newer "$T/port" -name lese 2>/dev/null)" ]'

echo "── 5. Vorprobe 404 (maven-metadata.xml fehlt) ──"
mv "$T/srv/repo/ch/plaintext/wurzel/maven-metadata.xml" "$T/weg.xml"
lauf 1.0.0 > "$T/l5.txt" 2>&1; rc=$?
pruefe "Exit 3 (rc=$rc)" '[ "$rc" -eq 3 ]'
pruefe "NICHT BEURTEILBAR gemeldet" 'grep -q "NICHT BEURTEILBAR" "$T/l5.txt"'
echo "── 6. --letzte 1 bei Vorprobe 404 ──"
lauf --letzte 1 > "$T/l6.txt" 2>&1; rc=$?
mv "$T/weg.xml" "$T/srv/repo/ch/plaintext/wurzel/maven-metadata.xml"
pruefe "Exit 1 (rc=$rc)" '[ "$rc" -eq 1 ]'
pruefe "1.0.0 NICHT BEURTEILBAR" 'grep -q "1.0.0  NICHT BEURTEILBAR" "$T/l6.txt"'

echo "── 7. Reposilite nicht erreichbar ──"
PATH="$T/bin:$PATH" REPOSILITE_BASIS="http://127.0.0.1:9/repo" \
  bash "$F/scripts/release-vollstaendig.sh" 1.0.0 > "$T/l7.txt" 2>&1; rc=$?
pruefe "Exit 3 (rc=$rc)" '[ "$rc" -eq 3 ]'

echo "── 8. GitHub-Vorprobe 404 ──"
mv "$T/srv/gh/ch/plaintext/wurzel/maven-metadata.xml" "$T/weg.xml"
lauf 1.0.0 > "$T/l8.txt" 2>&1; rc=$?
mv "$T/weg.xml" "$T/srv/gh/ch/plaintext/wurzel/maven-metadata.xml"
pruefe "Exit 3 (rc=$rc)" '[ "$rc" -eq 3 ]'

echo "── 9. PRUEFE_GITHUB=1 ohne Token ──"
PATH="$T/bin:$PATH" PRUEFE_GITHUB=1 GITHUB_TOKEN="" REPOSILITE_BASIS="http://127.0.0.1:$PORT/repo" \
  bash "$F/scripts/release-vollstaendig.sh" 1.0.0 > "$T/l9.txt" 2>&1; rc=$?
pruefe "Exit 3 (rc=$rc)" '[ "$rc" -eq 3 ]'

echo "────────────────────────────────────────"
echo "test-release-vollstaendig: $GRUEN gruen, $ROT rot"
[ "$ROT" -eq 0 ]
