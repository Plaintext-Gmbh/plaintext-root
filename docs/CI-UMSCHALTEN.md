# CI umschalten: GitHub Actions ↔ Woodpecker

Dieses Repo kann seine Pipeline von **GitHub Actions** oder von **Woodpecker**
(`https://ci.plaintext.ch`) fahren lassen — inklusive Release. Welches System zuständig
ist, steht in **einer Datei mit einem Wort**.

> **Stand 30.08.2026:** `.ci-engine` steht auf `github`. Die Woodpecker-Dateien sind
> vorhanden, aber **nicht scharf** — sie steigen bei jedem Lauf sofort wieder aus.
> `plaintext-root` ist in Woodpecker zudem noch gar nicht aktiviert und hat dort noch
> keine Secrets (Checkliste in Abschnitt 5).

`plaintext-root` ist das **letzte** Repo der Familie, das umgestellt wird; app, guild, iot
und schuetu fahren bereits über `.ci-engine=woodpecker`. Erst wenn auch root umgestellt ist,
können die self-hosted GitHub-Runner abgeschaltet werden — jedes root-Release läuft heute
noch dort.

---

## 1. Der Umschalter

```
.ci-engine     ← Repo-Root, genau ein Wort
```

| Inhalt        | Wirkung                                                              |
|---------------|----------------------------------------------------------------------|
| `github`      | GitHub Actions baut und released. Woodpecker steigt aus.              |
| `woodpecker`  | Woodpecker baut und released. GitHub Actions steigt aus.              |
| *Datei fehlt* | Wie `github` — ein Repo ohne Datei ändert sich also nicht.            |
| alles andere  | **Beide** Systeme brechen **rot** ab. Absicht, siehe unten.           |

Umschalten ist ein Einzeiler-Commit und damit im Git nachvollziehbar:

```bash
echo woodpecker > .ci-engine && git commit -am "CI: Woodpecker übernimmt" && git push
```

### Warum es den Wächter überhaupt braucht

Beide Systeme hängen am selben `push`-Ereignis auf `master`, und beide rufen dieselbe
Shell-Logik auf (`./build release` aus `plaintext-scripts` + dem lokalen `./build`). Ohne
Wächter würde ein Merge **zwei Releases** rechnen: zwei Versionsnummern, zwei Tags, zwei
Uploads in dieselben drei Ziele. Bei root wiegt das schwerer als bei den Apps — der
zweite Lauf verlöre nicht einen Container-Rollout, sondern führte zu einem halben
Artefakt-Release, das vier Konsumenten erben.

### Warum ein Tippfehler hart rot wird

Steht in `.ci-engine` etwas anderes als `github` oder `woodpecker` (auch: leere Datei),
brechen **beide** Wächter mit Fehler ab. Der Grund: beide lesen dieselbe Datei und steigen
bei allem aus, was nicht ihr eigener Name ist. Ein Tippfehler wie `woodpecke` würde also
sonst **beide** Systeme stilllegen — und zwar lautlos, mit lauter grünen Häkchen. Genau
dieser Zustand (nichts released, alles grün) ist der teuerste; auf der GitHub-Seite hat er
schon einmal einen CVE-Fix mit CVSS 9.1 ungerollt liegen lassen.

---

## 2. Was root anders macht als app / guild / iot / schuetu

Das ist der Kern dieser Portierung, deshalb steht es vor allem anderen.

### 2.1 `release-only` statt `release-all`

plaintext-root ist **das Framework, keine deploybare Anwendung**. Sein Container auf dem
NAS ist seit dem **12.08.2026 stillgelegt** (Karte 776); das **Maven-Artefakt** lebt weiter,
denn `plaintext-root-parent` ist der Parent-POM von app, guild, iot und schuetu — über ihn
kommt auch die Spring-Boot-Version.

`.github/workflows/ci-cd.yaml` setzt deshalb auf master `deploy-target: release-only`.
`.woodpecker/deploy.yml` tut dasselbe. `release-only` heißt: Version-Bump, Tag, Push,
`mvn clean deploy` — **kein Blue-Green, kein Container-Deploy**.

**Aber `release-only` heißt nicht „fasst das NAS nicht an".** plaintext-root trägt
`.m3-jar-volume`; `tui-build-logic.sh` setzt daraus `JAR_VOLUME_DEPLOY=true`, und
`do_release()` ruft nach dem `mvn deploy` **unbedingt** `stage_jar_to_nas()` auf: SSH nach
`mad@192.168.1.224`, Deploy-Lock, Jar nach `/volume1/docker/plaintext-root/jars/staging`.
Erst `deploy_to_dev` / `deploy_to_prod` bleiben aus. Deshalb braucht auch dieser Workflow
den SSH-Schlüssel — ohne ihn bricht der Lauf **nach** der Veröffentlichung ab.

### 2.2 Ein Release, drei Ziele

| # | Ziel | Wodurch |
|---|------|---------|
| 1 | **GitHub Packages** (`maven.pkg.github.com/Plaintext-Gmbh/plaintext-mvn`) | `<distributionManagement>`, server-id `plaintext` |
| 2 | **Reposilite auf dem NAS** (`maven.plaintext.ch/releases`) | Profil `dual-publish-nas`, server-id `plaintext-nas`, `altDeploymentRepository` |
| 3 | **`Plaintext-Gmbh/plaintext-mvn`** (git-basiertes Maven-Repo, ohne Login lesbar) | `mirror_to_plaintext_mvn()` im **lokalen `./build`** von root, nach erfolgreichem Release |

Ziel 2 wird **nicht** von der Pipeline aktiviert, sondern von `.mvn/maven.config`
(`-Droot.dual.publish=true`). Das gilt damit für **beide** CI-Motoren gleich, und deshalb
steht in keiner `.woodpecker/*.yml` ein `-P`-Schalter dafür. Wer ihn dort ergänzt, baut
eine zweite Wahrheit.

Ziel 3 braucht `GITHUB_TOKEN`. Fehlt es, meldet die Funktion „kein GITHUB_TOKEN —
Spiegelung übersprungen" und gibt **0** zurück: der Lauf bleibt grün, der öffentliche
Bezugsweg bleibt unbefüllt. `deploy.yml` setzt deshalb `GITHUB_TOKEN` aus demselben Secret
wie `MVN_DEPLOY_TOKEN` — wortgleich zur GitHub-Pipeline.

> **`deployAtEnd` nie wieder.** Der Versuch vom 29.08.2026 (Release 1.636.0) steht in
> `pom.xml:620`: Das Deploy-Plugin führt hier **zwei** Ausführungen aus (default → GitHub
> Packages, `deploy-nas` → Reposilite). Mit `deployAtEnd` endete das in einem **409** des
> Reposilite und einem halben Release. Die Reihenfolge bleibt, wie sie ist.

### 2.3 Kein `verify-dev.yml`, kein `verify-prod.yml`

iot, app, guild und schuetu haben je vier `.woodpecker`-Workflows. root hat **drei**. Die
beiden Verify-Dateien fehlen **absichtlich** — sie hätten in root nichts zu prüfen:

* In der geteilten GitHub-Pipeline steht am Job `verify-dev`
  `if: inputs.deploy-target != 'ci-only' && inputs.deploy-target != 'release-only' && inputs.dev-url != ''`.
  `release-only` ist dort **namentlich ausgeschlossen**, mit genau dieser Begründung
  (Karte 776): „es gibt dann auch kein DEV zu verifizieren … der Verify-Schritt würde eine
  stillgelegte Umgebung anpingen und den ansonsten gelungenen Release rot färben."
* `verify-prod` läuft überhaupt nur bei `if: inputs.deploy-target == 'release-all'`.

Beide Jobs sind für root also **heute schon** in jedem Lauf übersprungen. Die Dateien
mitzuschleppen hieße: zwei Workflows, die entweder nie starten (wenn man sie korrekt an
`release-all` hängt) oder eine seit dem 12.08.2026 tote Umgebung anpingen (Ports 1123/1124
sind stillgelegt) — und dann 7,5 Minuten lang „Waiting for DEV…" ins Log schreiben, bevor
sie mit einer Warnung enden. Beides ist schlechter als ihre Abwesenheit.

**Beim Wiederanschalten von root** (Container zurück, `release-all` statt `release-only`)
gehören sie zurück: `.woodpecker/verify-dev.yml` und `verify-prod.yml` aus `plaintext-iot`
kopieren, `DEV_URL` auf `http://192.168.1.224:1123` und `PROD_URL` auf die dann wieder
eingerichtete Cloudflare-Route setzen, und die Allowlist in `deploy.yml` (Schritt 0) um
`release-all` erweitern.

### 2.4 Kein Docker-Socket im Build

plaintext-app braucht ihn, weil dort Testcontainers-Tests im normalen Surefire-Lauf stehen.
**root hat keine** — Karte 451 (Entscheidung Daniels vom 02.08.2026) hat Testcontainers hier
durch `io.zonky.test:embedded-postgres` ersetzt, genau um den als root gemounteten
`/var/run/docker.sock` loszuwerden. Nachgezählt am 30.08.2026: `grep -ril testcontainers`
trifft nur noch Kommentare in zwei `pom.xml`, den Klassenkommentar von `EmbeddedPg` und
`.github/workflows/playwright.yaml`; **keine** Java-Klasse importiert die Bibliothek.

**Dafür ist die Test-Datenbank in root Pflicht, nicht Komfort.** `EmbeddedPg` startet nur
dann einen eigenen eingebetteten Server, wenn `SPRING_DATASOURCE_URL` **fehlt** — und
`initdb` verweigert den Dienst als `root`, was der Step-Container ist (gemessen 02.08.2026,
PR #22). Ohne die drei `SPRING_DATASOURCE_*`-Variablen fällt jede der rund zehn Testklassen
um, die `EmbeddedPg` nutzen, mit einer `initdb`-Meldung, die wie ein Agenten-Problem
aussieht und keines ist.

### 2.5 Zwei scharfe Gates, die die Konsumenten nicht haben

* **JaCoCo.** root setzt `<jacoco.halt-on-failure>true</jacoco.halt-on-failure>` bei
  `<jacoco.coverage.minimum>0.40</jacoco.coverage.minimum>` (Zustandsbericht 29.08.2026,
  Maßnahme 13). app/guild/iot/schuetu erben die Ausführung und schalten sie ab. Ein Modul
  unter 40 % färbt den Build hier **rot** — das ist der Sinn und kein Pipeline-Fehler. Wer
  es „repariert", hebt nicht den Schalter, sondern staffelt `<jacoco.coverage.minimum>` im
  betroffenen Modul (mit Begründung und Datum, nie 0).
* **Die geteilten Arch-Regeln** aus `plaintext-root-archtests` — root liefert sie selbst mit
  und ist damit sein eigener erster Konsument.

Dazu die schiere Größe: **24 Module**, und `plaintext-root-webapp` hat **zwei**
Surefire-Ausführungen (die zweite, `kontext-ohne-abwaehlbare-module`, startet einen eigenen
Spring-Kontext gegen dieselbe Datenbank). Die `MAVEN_OPTS`/`JAVA_TOOL_OPTIONS`-Leitplanken
in den Workflows sind hier weniger optional als anderswo.

---

## 3. Wie der Wächter auf beiden Seiten funktioniert

### GitHub-Seite

In der geteilten Pipeline `Plaintext-Gmbh/plaintext-scripts/.github/workflows/ci-cd-pipeline.yaml`
gibt es den Job **`ci-motor`**:

* läuft auf `ubuntu-latest` (belegt **keinen** NAS-Runner),
* checkt per *sparse checkout* **nur** die Datei `.ci-engine` aus,
* setzt den Output `zustaendig` auf `true` / `false`.

Die teuren Jobs hängen daran: `ci`, `sonar`, `deploy` direkt, `verify-dev`/`verify-prod`
über `needs: deploy`. `namespace-lint` bleibt **absichtlich ungesperrt** (kostet nichts
Knappes, und die Namespace-Leitplanke soll nicht genau dann verschwinden, wenn am
Build-System geschraubt wird). **Folge:** ein Repo im Woodpecker-Modus hat weiterhin je Push
einen GitHub-Lauf, der genau diesen einen Job ausführt.

> **Noch offen (anderer Agent, Stand 30.08.2026):** geplante Läufe (`schedule`) sind vom
> `ci-motor` **noch ausgenommen**. Bis das portiert ist, laufen die GitHub-Crons von root
> (`0 2 * * *` nightly, `0 4 * * 2` Voll-Analyse) also **weiter**, auch wenn `.ci-engine`
> auf `woodpecker` steht. Das ist beim Umschalten einzuplanen: sonst analysieren beide
> Systeme, und die GitHub-Seite committet `quality/quality-gate.properties` zurück.

Zwei weitere Workflows dieses Repos hängen **nicht** an der geteilten Pipeline und laufen
unabhängig vom Umschalter weiter:

* `.github/workflows/playwright.yaml` — die UI-Tests. Sie blockieren keinen Merge und haben
  in Woodpecker bewusst keine Entsprechung (sie brauchen einen Chromium-Download je Lauf).
* `.github/workflows/housekeeping.yml` — nur `workflow_dispatch`, Log-Aufräumen.

### Woodpecker-Seite

`.woodpecker/waechter.sh` wird von **jedem** Step als erstes Kommando **gesourct**:

```yaml
commands:
  - . .woodpecker/waechter.sh
```

Der führende Punkt ist entscheidend: das `exit 0` im Skript beendet dann den Step selbst —
mit **Erfolg**, aber ohne etwas getan zu haben. *Nicht zuständig* ist kein Fehler; ein rotes
Symbol wäre nach zwei Tagen Hintergrundrauschen.

Warum kein `when:`-Filter? Woodpecker kann `when` nur gegen Ereignis, Branch, Pfad und
Umgebungsvariablen auswerten, nicht gegen den **Inhalt** einer Datei im Repo. Eine
Repo-Variable in der Woodpecker-Oberfläche könnte das — dann stünde der Umschalter aber in
einer Datenbank auf dem NAS statt im Git.

---

## 4. Woran man sieht, welches System gerade fährt

1. **`cat .ci-engine`** — die Quelle der Wahrheit.
2. **GitHub-Lauf ansehen:** der Job `CI-Motor (.ci-engine)` schreibt seinen Befund in die
   Job-Summary.
3. **Woodpecker-Lauf ansehen** (`https://ci.plaintext.ch`): steht im ersten Step
   „AUSSTIEG: .ci-engine sagt 'github'", hat Woodpecker nichts getan.
4. **Am Ergebnis:** wer das letzte Release gefahren hat, steht im Autor des
   `Release version …`-Commits — `GithubActions` bzw. `WoodpeckerCI`.

---

## 5. Was in Woodpecker eingerichtet werden muss

Nichts davon macht dieser PR — er ändert nur Dateien im Repo.

| Nr. | Was                                                                                                    |
|-----|--------------------------------------------------------------------------------------------------------|
| 1   | **Repo aktivieren.** `plaintext-root` ist in Woodpecker noch nicht aktiviert. Das Aktivieren legt den GitHub-Webhook an. Wie bei den vier Apps auf `trusted.volumes` setzen. |
| 2   | **Secrets anlegen** (Tabelle unten), Namen **kleingeschrieben**.                                        |
| 3   | **Crons anlegen:** `nightly` und `wochenanalyse`. Die Namen sind in `build.yml` / `sonar.yml` als `cron:`-Filter fest verdrahtet. Woodpecker definiert Crons **nicht** in der YAML, sondern unter *Repo → Settings → Crons*. Die GitHub-Zeiten von root sind `0 2 * * *` bzw. `0 4 * * 2` (Dienstag); die Staffelung über die Repos stammt aus Karte 889 und sollte erhalten bleiben. |
| 4   | **Timeout anheben.** Woodpecker kennt kein Step-Timeout in der YAML; das Limit ist eine Repo-Einstellung (Vorgabe 60 min). Ein kalter Sonar-Lauf über 24 Module braucht ~25 min, ein Release-Lauf mit vollem Test-Durchlauf ähnlich viel — 90 min sind ein vernünftiger Wert. |
| 5   | *(optional, für die Voll-Analyse)* am **Agenten** ein zweites globales Volume: `WOODPECKER_BACKEND_DOCKER_VOLUMES=woodpecker-m2:/root/.m2,woodpecker-odc:/root/.cache/owasp-dc-data`. Siehe Abschnitt 7. |

### Secrets

Alle Werte existieren bereits als GitHub-Org-Secrets bzw. im Vaultwarden (Org `claude`).
**Nicht** in Dateien oder Commits schreiben.

| Woodpecker-Name       | Zweck                                                                    | Herkunft                                     | gebraucht in            |
|-----------------------|--------------------------------------------------------------------------|----------------------------------------------|-------------------------|
| `mvn_deploy_token`    | GitHub-PAT. **Vier Aufgaben in root:** Push von Release-Commit und Tag; server-id `plaintext` (GitHub Packages); Klon von `plaintext-scripts` und `plaintext-config`; und — als `GITHUB_TOKEN` gespiegelt — der Spiegel nach `plaintext-mvn` sowie das GitHub-Release mit Notes | GitHub-Org-Secret `MVN_DEPLOY_TOKEN`         | `deploy.yml`            |
| `maven_nas_token`     | Reposilite-Token (`maven.plaintext.ch`), server-id `plaintext-nas` für die zweite Deploy-Ausführung | GitHub-Org-Secret `MAVEN_NAS_TOKEN`, Vault „Reposilite CI-Token" | `deploy.yml`            |
| `ssh_private_key`     | SSH-Schlüssel für `mad@192.168.1.224` — **auch bei `release-only`**, siehe 2.1 (`stage_jar_to_nas`) | GitHub-Org-Secret `SSH_PRIVATE_KEY`          | `deploy.yml`            |
| `sonar_token`         | SonarQube-Analysetoken (`sonar.plaintext.ch`)                            | GitHub-Org-Secret `SONAR_TOKEN`              | `sonar.yml`             |
| `pushover_app_token`  | Meldung „Sonar-Analyse fehlgeschlagen"                                   | GitHub-Org-Secret `PUSHOVER_APP_TOKEN`       | `sonar.yml`             |
| `pushover_user_key`   | dito                                                                      | GitHub-Org-Secret `PUSHOVER_USER_KEY`        | `sonar.yml`             |
| `nvd_api_key`         | *(noch nicht gebraucht)* — erst wenn die OWASP-Analyse portiert wird       | GitHub-Org-Secret `NVD_API_KEY`              | —                       |

**`TWINGATE_SERVICE_KEY` wird nicht gebraucht.** Der Woodpecker-Agent steht im LAN auf dem
NAS; der Tunnel war nur für GitHub-hosted Runner nötig.

> **Wichtig bei jedem Secret:** unter *Events* **nur** `push`, `manual` und `cron` erlauben,
> **nicht** `pull_request`. Ein PR-Lauf braucht keinen SSH-Schlüssel und kein Deploy-Token.
> Woodpecker macht das nicht von selbst richtig.

### plaintext-root ist öffentlich — anders als die vier Apps

Das ist der einzige Punkt, an dem root beim Motorwechsel **mehr** Aufmerksamkeit braucht als
iot/app/guild/schuetu. Auf der GitHub-Seite laufen PRs dieses Repos seit Karte 426 (Entscheidung
Daniels, 02.08.2026) **nicht mehr** auf den NAS-Runnern, sondern auf `ubuntu-latest` — begründet
mit genau dieser Kombination: schreibend gemounteter Docker-Socket, Job als root, öffentliches
Repo, also ein Weg für Fremde in die `.env`-Dateien des NAS.

Für Woodpecker heißt das:

* **Die Secret-Events sind hier eine Sicherheitsmaßnahme, keine Hygiene.** Ein Fork-PR darf
  `.woodpecker/build.yml` beliebig ändern; er darf nur nichts vorfinden, womit er etwas anfangen
  kann. `build.yml` referenziert deshalb **kein einziges** Secret.
* **Das geteilte `woodpecker-m2:/root/.m2` hängt auch im PR-Lauf.** Deshalb schreibt `deploy.yml`
  seine `settings.xml` nach `/tmp/ci/` und räumt in einem eigenen `aufraeumen`-Step (mit
  `status: [success, failure]`) eine eventuell dort liegengebliebene Datei aus dem Volume —
  dieselbe Falle wie Karte 313 auf den GitHub-Runnern.
* **Der Agent selbst bleibt die Grenze.** Woodpecker führt PR-Läufe im selben Docker-Daemon aus;
  ob PR-Läufe von Forks für dieses Repo überhaupt erlaubt sein sollen, ist eine
  Repo-Einstellung (*Allow pull requests*) und gehört beim Aktivieren bewusst entschieden.

---

## 6. Aufteilung der `.woodpecker/`-Dateien

Woodpecker führt jede Datei unter `.woodpecker/` als eigenen **Workflow** aus, parallel;
Reihenfolge nur über `depends_on`.

| Datei             | entspricht GitHub-Job | Auslöser                                     | Besonderheit |
|-------------------|-----------------------|-----------------------------------------------|--------------|
| `build.yml`       | `ci`                  | `pull_request`, `manual`, Cron `nightly`      | **kein** `push:master` — dort baut `deploy.yml` (M1 „build once") |
| `sonar.yml`       | `sonar` (Teil)        | Cron `wochenanalyse`, `manual`                | Voll-Analyse **nicht** portiert, s. u. |
| `deploy.yml`      | `deploy`              | `push:master`, `manual`                        | ein einziger Step; `release-only` |
| `waechter.sh`     | —                     | —                                              | kein Workflow (Woodpecker liest nur `*.yml`/`*.yaml`) |
| ~~`verify-dev.yml`~~ | `verify-dev`       | —                                              | **bewusst nicht angelegt**, siehe 2.3 |
| ~~`verify-prod.yml`~~| `verify-prod`      | —                                              | **bewusst nicht angelegt**, siehe 2.3 |

`deploy.yml` ist **ein** Step. Das ist Woodpeckers Modell, nicht Bequemlichkeit: jeder Step
ist ein eigener Container, geteilt wird nur das Workspace-Volume. `$HOME/codeplain`, `~/.ssh`
und `/tmp` eines Steps gibt es im nächsten Step nicht mehr. Die GitHub-Pipeline kann das auf
acht Steps verteilen, weil dort alle Steps im selben Runner-Container laufen.

### Manuelle Läufe: die Allowlist ersetzt eine gelöschte Auswahloption

Im GitHub-UI ist `release-all` seit Karte 904 **absichtlich nicht mehr wählbar**: es endet in
`sudo docker compose up -d --no-deps --force-recreate <slot>`, und `profiles: ["stillgelegt"]`
greift **nicht**, sobald ein Service namentlich gestartet wird (gemessen 13.08.2026 auf dem
NAS). Genau so ist die Stilllegung am 17.08.2026 versehentlich aufgehoben worden — ein bloßer
Kommentar hat das nicht verhindert, weil man im Dialog nur die Optionsnamen sieht.

Woodpeckers „Run pipeline"-Dialog kennt **keine Auswahlliste**, sondern nur ein Textfeld für
Variablen. Die Begrenzung steht deshalb als **Allowlist in Schritt 0 von `deploy.yml`**:
`ci-only` und `release-only` sind erlaubt, alles andere bricht mit einer Meldung ab, die die
Karte nennt. Ohne die Variable tut ein manueller Lauf gar nichts — die sichere Richtung.

```
Run pipeline → Variable:  deploy_target = release-only
```

### Postgres

In Woodpecker über `services:`. Der Container ist unter seinem **Namen** (`postgres`) auf
Port 5432 erreichbar. Damit entfällt die komplette Portvergabe der GitHub-Seite (root: 5441,
Deploy-Job 5541; „nicht auf 5434 zurückstellen", weil die UGREEN-Photo-App den Port belegt):
jeder Workflow hat sein eigenes Netz, zwei gleichzeitige Läufe können sich nicht am Port
treffen, und der Container verschwindet mit dem Lauf. **Aber:** Woodpecker startet Services
und Steps gleichzeitig und kennt kein Health-Gate — jeder Step wartet deshalb selbst in einer
Schleife und bricht nach 120 s mit einer eindeutigen Meldung ab.

### Step-Image

`maven:3.9-eclipse-temurin-25` bringt mit: `git`, `ssh`, `scp`, `curl`, `tar`, `gzip`,
Maven **3.9.16**, JDK 25, `HOME=/root`. Es fehlt: `docker`, `gh`, `python3`, `jq`, `psql`.

* **Build, Sonar:** das Image **reicht**.
* **Release:** es fehlen **zwei** Binaries, mit sehr unterschiedlichem Gewicht:
  * **`docker` — zwingend, ohne Auffangnetz.** Nicht zum Bauen (root trägt
    `.m3-jar-volume`, ruft weder `docker build` noch `docker save`, und `release-only` fasst
    ohnehin keinen Container an), sondern weil `tui-build-logic.sh` beim **Sourcen** eine
    Container-Laufzeit sucht und ohne Fund `exit 1` macht — `./build` stürbe, bevor es
    irgendetwas tut.
  * **`gh` — best-effort, mit Auffangnetz.** `release_notes_erzeugen()` legt zum eben
    gepushten Tag ein GitHub-Release mit generierten Notes an. Fehlt `gh`, steigt die
    Funktion mit einer gelben Warnung aus und gibt **0** zurück: der Lauf bleibt grün, das
    Release fehlt lautlos. Auf den NAS-Runnern ist `gh` im Runner-Image — für root wäre der
    Motorwechsel ohne diesen Nachbau also ein **stiller** Funktionsverlust (geprüft
    30.08.2026: die GitHub-Releases 1.643.0 bis 1.647.0 existieren alle). iot/app/guild/
    schuetu installieren `gh` nicht; dort ist das Release Beiwerk, bei root ist die
    Veröffentlichung das ganze Produkt des Laufs.

**Beides ist die provisorische Lösung.** Ein Release sollte nicht an zwei Downloads von
fremden Hosts hängen. Empfohlen: ein eigenes Step-Image, dann ändert sich nur die
`image:`-Zeile.

```dockerfile
# plaintext-ci:jdk25 — auf dem NAS bauen, bleibt lokal im Docker-Daemon des Agenten
FROM maven:3.9-eclipse-temurin-25
ARG DEBIAN_FRONTEND=noninteractive
ARG DOCKER_CLI_VERSION=28.1.1
ARG GH_VERSION=2.63.2
RUN apt-get update \
 && apt-get install -y --no-install-recommends jq python3 unzip postgresql-client \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" \
      | tar -xz -C /usr/local/bin --strip-components=1 docker/docker \
 && curl -fsSL "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_amd64.tar.gz" \
      | tar -xz -C /usr/local/bin --strip-components=2 --wildcards '*/bin/gh'
```

```bash
# auf dem NAS:
sudo docker build -t plaintext-ci:jdk25 .
# danach in den .woodpecker/*.yml:  image: plaintext-ci:jdk25
```

---

## 7. Was **nicht** portiert ist

Ehrlich benannt, statt überspielt.

### 7.1 Concurrency-Gruppen — und die Versionsrechnung

**Das ist die wichtigste Lücke dieser Portierung.**

Woodpecker kennt **keine** `concurrency:`-Gruppen. Auf der GitHub-Seite serialisiert
`deploy-plaintext-root` (`cancel-in-progress: false`) alle Release-Läufe dieses Projekts.

Was **weiterhin schützt**:

* Der **Deploy-Lock auf dem NAS** (`deploy_lock_acquire`, atomares `mkdir`, Übernahme
  verwaister Locks nach 1 h, seit 29.08.2026). Er schützt das **Jar-Staging** systemunabhängig
  — eingeführt genau deshalb, weil GitHubs Gruppe einen lokalen `./build`-Lauf nicht kennt.
* Die **Kollisionsprüfung gegen den Reposilite** *vor* dem Build (Karte 410): ein HTTP-HEAD
  auf `…/plaintext-root-parent/<neue Version>/….pom`; ist sie schon da, bricht `do_release`
  ab, bevor irgendetwas passiert.
* Der **abgelehnte `git push`** (Karte 518): Release-Commit und Tag gehen raus, **bevor**
  veröffentlicht wird. Kommt der zweite Lauf zu spät, wird sein Push abgelehnt, und er
  bricht ab — mit der ausdrücklichen Meldung „Es wurde NICHTS veröffentlicht; der Zustand ist
  konsistent."

Was **nicht** schützt:

* Nichts hindert zwei gleichzeitig gestartete Läufe daran, **dieselbe Versionsnummer zu
  rechnen**. Beide lesen dieselbe POM-Version von `master`, beide bestehen die
  Kollisionsprüfung (die Version ist ja noch nirgends), beide bauen. Erst am `git push`
  fliegt der zweite raus. Das kostet einen kompletten Build-Durchlauf, hinterlässt aber
  keinen kaputten Zustand.
* **Das Fenster ist klein, aber es ist da.** Es öffnet sich zwischen dem `git fetch` des
  zweiten Laufs und dem `git push` des ersten — bei root sind das die Minuten eines
  24-Modul-Builds mit Unit-Tests. Realistisch entsteht es nur, wenn innerhalb dieser Minuten
  ein zweiter Push auf `master` landet **und** der Agent (`WOODPECKER_MAX_WORKFLOWS=2`)
  gerade einen zweiten Slot frei hat.
* **Kein `cancel-in-progress` pro Zweig.** Woodpecker hat dafür nur eine Repo-Einstellung
  („cancel previous pipelines"), die nicht zwischen `master` und PR-Zweigen unterscheidet.
  Auf der GitHub-Seite ist genau das getrennt (`cancel-in-progress: ${{ github.ref !=
  'refs/heads/master' }}`).

**Praktische Regel, bis das gelöst ist:** während ein root-Release läuft, keinen zweiten
Merge auf `master` schieben. Das ist dieselbe Regel, die für lokale `./build`-Läufe ohnehin
gilt.

### 7.2 Die wöchentliche Voll-Analyse (OWASP-CVE, SpotBugs, Quality-Gate)

`sonar.yml` macht die **Sonar-Analyse**, aber **nicht** `quality-analysis`: kein
OWASP-Dependency-Check, kein SpotBugs, keine Gate-Bewertung, kein Rückcommit von
`quality/quality-gate.properties`.

**Grund:** der CVE-Scan braucht einen persistenten NVD-Datenbestand. Auf den GitHub-Runnern
liegt der in einem benannten Volume (`odc-cache`) unter `$HOME/.cache/owasp-dc-data`. Der
Woodpecker-Agent hängt in Step-Container **nur** `woodpecker-m2:/root/.m2`; ein untrusted
Repo darf sich selbst kein Volume mounten. Ohne Bestand lädt dependency-check bei **jedem**
Lauf die komplette NVD neu — gemessen ~96 min, regelmäßig im Timeout. Dann entsteht **kein**
Report, und `quality-gate.py` wertet einen fehlenden Report ausdrücklich als „kein Breach":
ein **grünes Gate, weil nichts gemessen wurde**. Genau diesen Zustand hat die GitHub-Seite
über Monate teuer gelernt (Karten 365, 420, 896, 925) — plaintext-root war eines der Repos,
deren Gate deshalb wochenlang fälschlich auf OK stand.

**Bei root wiegt die Lücke schwerer als bei den Apps:** root ist der Parent-POM von app,
guild, iot und schuetu, und die meisten CVEs der Familie kommen über sein
`<dependencyManagement>`. Ein CVE-Scan, der hier ausfällt, fällt für alle vier Konsumenten
aus. Renovate läuft weiter — er meldet neue Versionen, nicht CVEs.

**Voraussetzungen zum Schließen:** (1) Agent-Volume `woodpecker-odc`, (2) `python3`+`jq` im
Step-Image, (3) `nvd_api_key` als Secret.

### 7.3 Weitere Lücken

| GitHub | Woodpecker | Bewertung |
|---|---|---|
| `$GITHUB_STEP_SUMMARY` (Coverage-Tabelle, Deploy-Summary) | — | Ersatz: Ausgabe ins Step-Log. Funktional gleichwertig, optisch nicht. |
| `actions/upload-artifact` (Testberichte, Screenshots bei Fehlschlag) | — | Woodpecker hat keinen eingebauten Artefakt-Speicher. Für root heute folgenlos (Uploads nur `if: failure()`, niemand konsumiert sie). |
| `timeout-minutes` je Job/Step | Repo-Einstellung | Grobkörniger; der 180-min-Wert des Sonar-Jobs lässt sich nicht getrennt setzen. |
| `concurrency: cancel-in-progress` für PR-Zweige | Repo-Einstellung „cancel previous pipelines" | Vorhanden, aber nicht pro Zweig unterscheidbar — siehe 7.1. |
| `workflow_dispatch` mit Auswahlfeld | Variable im „Run pipeline"-Dialog + Allowlist in `deploy.yml` | Die Allowlist ersetzt die gelöschte `release-all`-Option, siehe Abschnitt 6. |
| `namespace-lint` | — | bleibt bewusst auf GitHub. |
| `playwright.yaml` | — | bleibt bewusst auf GitHub; blockiert ohnehin keinen Merge. |
| `schedule`-Läufe | noch nicht vom `ci-motor` gesperrt | **Offen**, wird von anderer Seite portiert — siehe Abschnitt 3. |
| Twingate | entfällt | Agent steht im LAN. |

---

## 8. Fallen, die beim Portieren aufgefallen sind

* **`CI=woodpecker`.** Woodpecker setzt das von sich aus. `tui-build-logic.sh` prüft aber auf
  den **Wert** `"true"` — sonst liefe der **Vorflug des Lokal-Release** mit, der den
  Arbeitsbaum prüft und per `gh` nach laufenden GitHub-Actions-Läufen fragt. Dieselbe
  Variable wählt außerdem `docker` als Container-Laufzeit (Zeile 33 ff.). `deploy.yml` setzt
  deshalb ausdrücklich `CI: "true"`.
* **Der Verzeichnisname zählt.** `load_build_conf()` leitet den Namen der Konfiguration aus
  dem **Basename des Arbeitsverzeichnisses** ab (`plaintext-config/<name>/build-conf.txt`).
  Aus dieser Datei kommt bei root unter anderem `MVN_RELEASE_DEPLOY=true` — also der
  Unterschied zwischen `mvn clean deploy` und `mvn clean package`. Sie zu verfehlen hieße:
  grüner Lauf, nichts veröffentlicht. Woodpeckers Vorgabe-Workspace endet auf
  `…/plaintext-root` und passt — `deploy.yml` prüft es trotzdem und bricht klar ab.
* **`/root/.m2` ist ein GETEILTES Volume** über alle Repos. Eine `settings.xml` dort wäre für
  jeden fremden Lauf lesbar — dieselbe Falle wie Karte 313 auf den GitHub-Runnern. Deshalb
  liegt sie in `/tmp/ci/` (Schreibschicht dieses einen Containers) und wird über `MAVEN_ARGS`
  eingebunden. Ein mvn-Shim wie auf GitHub ist nicht nötig: das Image ist auf Maven 3.9.16
  festgelegt, und `MAVEN_ARGS` trägt ab 3.9.
* **Das geteilte `~/.m2/repository` und der Spiegel.** `mirror_to_plaintext_mvn()` sucht die
  zu spiegelnden Artefakte per Glob `~/.m2/repository/ch/plaintext/*/<version>`. Alle fünf
  Repos der Familie liegen unter derselben groupId `ch.plaintext`; ein Konsument-Modul mit
  **exakt derselben Versionsnummer** wie das root-Release würde also mitgespiegelt. Heute
  liegen die Nummern weit auseinander (root 1.647, guild 1.435, iot 1.346, app 2.17xx), und
  auf den GitHub-Runnern ist `~/.m2` ebenfalls ein geteiltes Volume — das ist also **keine
  Verschlechterung durch Woodpecker**, sondern eine Eigenschaft des geteilten lokalen
  Repositorys, die hier nur festgehalten gehört.
* **`do_release` macht `git add -A`.** Alles, was im Repo-Verzeichnis liegt, landet im
  Release-Commit. Deshalb steht **kein** Geheimnis im Workspace — Token im
  credential-Helper unter `/tmp`, nicht in der Remote-URL, und die `settings.xml` in `/tmp/ci`.
* **Der Klon muss voll sein und Tags haben.** `init_versions()` liest die letzte
  Release-Nummer aus `git describe --tags`; ein flacher Klon liefert `0.0.0`. Bei root kommt
  dazu: `mirror_to_plaintext_mvn()` nimmt die Version **aus dem Tag**, nicht aus dem POM (das
  steht zu dem Zeitpunkt schon auf dem nächsten SNAPSHOT). Zusätzlich stellt `deploy.yml` den
  Zweig explizit her (`git checkout -B`, `--set-upstream-to`).
* **Release-Commits lösen keinen Gegenlauf aus.** `do_release` schreibt
  `Release version X [skip ci]`; sowohl GitHub als auch Woodpecker erkennen den nativen
  Marker und legen gar keinen Lauf an.
* **`commands:`-Einträge müssen Strings sein.** Ein ungequoteter Eintrag mit `: ` darin wird
  von YAML zur **Map**, und Woodpecker verwirft dann den **kompletten** Lauf
  (`cannot unmarshal map[...] into a string value`). Daran ist der erste iot-Lauf gescheitert
  (iot #177). Die drei Dateien dieses Repos sind mit einem echten Parser gegengeprüft — jeder
  Eintrag ist `!!str`, und jeder ist zusätzlich durch `sh -n` gelaufen.
* **Der `manual`-Event muss im `when:` stehen**, sonst verwirft Woodpecker den Lauf mit
  „filtered out all steps" (schuetu-Probelauf, 30.08.2026).

---

## 9. Zurückschalten — Checkliste

`.ci-engine` wieder auf `github` setzen. Zusätzlich beachten:

* **Ein laufendes Release erst zu Ende laufen lassen.** Der Umschalt-Commit ist ein Push auf
  `master` und löst im Zielsystem sofort einen Release-Lauf aus.
* **Halb fertige Releases prüfen.** Bricht ein Woodpecker-Release nach `git push` des
  Release-Commits ab, ist der Tag draußen und das Artefakt eventuell nicht — bei root sogar
  „in einem von drei Zielen". `git fetch --tags && git log --oneline -3` vor dem Umschalten,
  und im Zweifel `https://maven.plaintext.ch/releases/ch/plaintext/plaintext-root-parent/`
  gegen die Tag-Liste halten.
* **Die Woodpecker-Crons abschalten.** Ein Woodpecker-Cron, der auf ein Repo im
  `github`-Modus feuert, erzeugt nur einen leeren grünen Lauf — Rauschen, kein Schaden.
* **Der Umschalt-Commit selbst darf keinen Skip-Marker tragen.** Sonst startet im neuen
  System gar nichts und man hält den leeren Zustand für Erfolg.
