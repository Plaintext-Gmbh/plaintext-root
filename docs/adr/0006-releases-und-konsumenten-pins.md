# Releases von root und das Nachziehen der Konsumenten (Auto-Bump, Pins)

* **Status:** accepted
* **Date:** 2026-08-29 (nachträglich festgehalten; Karten 322, 776, 942)
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root` ist eine Bibliothek mit vier Konsumenten (`plaintext-app`, `-guild`,
`-schuetu`, `-iot`), die `plaintext-root-parent` als Parent-POM haben und so auch ihre
Spring-Boot-Version von hier beziehen. Der eigene Container von root ist seit 12.08.2026
stillgelegt (Karte 776) — es gibt nichts mehr zu deployen, aber ohne Releases von hier hätte
keine App einen Weg zu einer neuen root-Version.

Drei Dinge mussten geklärt sein: Wie entsteht ein root-Release, wie kommt er in die Apps,
und wie verhindert man, dass das Aufräumen des Maven-Repos eine Version löscht, auf der eine
App noch steht.

## Decision

1. **Jeder Merge nach `master` ist ein Release** (`ci-cd.yaml` → `release-only`): Bump, Tag,
   `mvn clean deploy` nach `maven.plaintext.ch/releases` **und** GitHub Packages
   (Dual-Publish), anschliessend `Prepare next development iteration … [skip-ci]`. Kein
   Container-Deploy. Die Minor-Nummer zählt Releases und ist kein SemVer-Versprechen;
   Kompatibilität steht im `CHANGELOG.md`-Text.
2. **Die Konsumenten ziehen — nicht root schiebt** (`root-autobump.yaml` in jedem
   Consumer-Repo, Karte 322): Der Workflow liest die `maven-metadata.xml` des NAS-Repos
   (nicht den Git-Tag — der entsteht vor dem `deploy`), setzt Parent-Version und
   `<plaintext-root.version>`, **baut zur Prüfung** und öffnet erst dann einen PR. Roter
   Build → kein PR, nur Pushover. **Kein Auto-Merge**: der Merge im Consumer ist dessen
   Deploy und bleibt eine bewusste, serielle Handlung.
3. **Jeder Consumer meldet seinen Pin** (`publish-root-pin.yaml`, Karte 942) nach
   `Plaintext-Gmbh/plaintext-mvn`, Branch `pins`, bei jedem master-Push mit `pom.xml`-Änderung
   und wöchentlich als Heartbeat. Der Aufräumer dort löscht keine gepinnte Version und bricht
   ab, wenn ein Pin älter als 30 Tage ist.

## Consequences

* **Positiv:** Ein kaputter root-Release kann sich nicht über die Apps verteilen — der
  Verify-Build im Consumer hält ihn auf, bevor ein PR existiert.
* **Positiv:** Kein cross-repo-PAT, kein Push-Recht von root in die Apps; die Richtung
  «ziehen» kommt mit dem Token aus, das ohnehin existiert.
* **Negativ:** Latenz. Die Apps hängen bis zu einem Tag (zwei Cron-Fenster) hinter root;
  ein dringender Fix braucht `workflow_dispatch` oder einen Bump von Hand.
* **Negativ:** Ein Bump-PR pro App und root-Release — bei täglichen root-Releases ist das
  sichtbarer Review-Aufwand, den niemand automatisiert wegnimmt (bewusst).
* **Negativ:** Ein grüner Build beweist keine funktionierende Laufzeit (fwtool fiel nach
  einem Bump mit 502, weil Vault-Variablen fehlten — Build war grün). Der Merge bleibt
  darum ein Mensch.
* **Neutral:** Die Versionsnummer von root steigt schnell (dreistellige Minor). Das ist die
  Folge von «jeder Merge ein Release» und kein Fehler.

## Alternatives considered

| Option | Why not? |
| --- | --- |
| root stösst per `repository_dispatch` Bumps in den Apps an (Push) | Braucht ein cross-repo-PAT; Org-Secrets lösen auf dem Free-Plan in privaten Repos still zu leer auf — ein weiterer stiller Fehlerpfad. |
| Auto-Merge der Bump-PRs | Parallele Consumer-Deploys recyceln die NAS-Runner mitten im Blue-Green (PROD 502); und grün ≠ läuft. |
| `versions:update-property` für den Bump | Liefert falsche Vorschläge, weil `${plaintext-root.version}` über `${plaintext.version}` auch an Artefakte anderer Versionslinien hängt (Messung 30.07.2026 in iot). |
| Git-Tag als Versionsquelle | Der Tag entsteht vor dem `mvn deploy`; ein Tag ohne Artefakt ergäbe einen unauflösbaren Bump. |
| Pins durch den Aufräumer aus den App-POMs lesen | Die App-Repos sind privat; der Token von `plaintext-mvn` kommt nicht hinein. Deshalb schieben die Apps. |

## References

* `.github/workflows/ci-cd.yaml` (Kopfkommentar zu Karte 776 / `release-only`)
* Consumer-Repos: `.github/workflows/root-autobump.yaml`, `.github/scripts/root-autobump.sh`,
  `.github/workflows/publish-root-pin.yaml`
* `plaintext-scripts/.github/workflows/ci-cd-pipeline.yaml`
* `CLAUDE.md` (Release-Ablauf, Port-Tabelle)
