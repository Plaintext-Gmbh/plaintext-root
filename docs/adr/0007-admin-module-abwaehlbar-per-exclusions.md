# Admin-Module abwählen: Maven-`<exclusions>` statt `optional`, BOM oder `@ConditionalOnProperty`

* **Status:** accepted
* **Date:** 2026-08-30 (Zustandsbericht 29.08.2026, §3 „Aggregator ohne Opt-out")
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root-webapp` zieht 20 interne Module, keines davon `optional`. Eine App hängt an
genau diesem einen Artefakt und bekommt damit alles: `plaintext-schuetu` mit 5,9k fachlichen
Zeilen schleppt Webhooks, Benachrichtigungen, Secrets, OIDC und Mailvorlagen mit — Code,
Classpath und CVE-Fläche. `plaintext-admin-modules` schaltet nur die **Menü-Sichtbarkeit** ab
(`plaintext.menu.module-roles.<key>`), nicht den Classpath.

Der interne Abhängigkeitsgraph ist flach:

```
plaintext-root-interfaces          (Verträge, keine Abhängigkeit)
  └── plaintext-root-menu, plaintext-root-common
        └── alle übrigen root-*- und admin-*-Module
```

Kein Admin-Modul hängt an einem anderen Admin-Modul; alle zwölf sind Blätter. Die Verträge
(`IMailTemplateProvider`, `ISetupConfigService`, `ModuleEnablementProvider`, `SecretResolver`,
`NotificationService`, …) liegen bereits in `plaintext-root-interfaces` — die Trennung war also
schon vorgedacht, nur nie ausgenutzt.

## Decision

**Die Module bleiben nicht-`optional` in `plaintext-root-webapp`; wer sie loswerden will, nimmt
Maven-`<exclusions>`.** Dazu ein Testlauf, der beweist, dass der Kontext ohne sie startet.

Die drei erwogenen Alternativen scheiden aus konkreten Gründen aus:

1. **`<optional>true</optional>` an den Dependencies.** Kehrt die Vorgabe von „alles an" auf
   „nichts an" um. Alle vier Apps hängen an genau einem Artefakt und würden beim nächsten
   root-Bump **stillschweigend** Module verlieren, die sie benutzen — genau die
   Verhaltensänderung, die dieser Umbau nicht haben darf. `optional` wird erst tragfähig, wenn
   die Apps ihre Module selbst deklarieren; das ist ein Schnitt in vier fremden Repos und
   gehört nicht in diesen Schritt.
2. **Eigene BOM `plaintext-root-bom`.** Wäre eine zweite, parallel zu pflegende Kopie derselben
   Liste: `plaintext-root-parent` **ist** bereits die BOM — sein `dependencyManagement` verwaltet
   jedes interne Artefakt über `${plaintext-root.version}`, und alle vier Apps haben genau diesen
   Parent. Eine BOM brächte hier keinen einzigen Fall, der ohne sie nicht ginge.
3. **`@ConditionalOnProperty` an den Modul-AutoConfigurations.** Wirkungslos. Die Beans der
   Module hängen in root **und in allen vier Apps** am `@ComponentScan("ch.plaintext")` der
   Boot-Klasse, nicht an ihrer AutoConfiguration — nachgeprüft in allen vier Repos. Ein
   ausgeschalteter Schalter hätte die AutoConfiguration abgeräumt und die Beans stehen lassen:
   ein Schalter, der aussieht, als schaltete er.

Abwählbar sind heute vier Module: `plaintext-admin-webhooks`, `-notifications`, `-secrets`,
`-modules`. Welche nicht und warum, steht in `docs/MODULE_ABWAEHLEN.md`.

## Consequences

* **Positiv:** Eine App kann Module streichen, ohne dass die anderen drei etwas merken. Für die
  bestehenden Apps ändert sich nichts — die Vorgabe bleibt „alles an".
* **Positiv:** Die Abwählbarkeit ist nicht länger ein Zufall, den der nächste `@Autowired`
  wieder kassiert. Der Surefire-Lauf `kontext-ohne-abwaehlbare-module` in
  `plaintext-root-webapp` nimmt den vier Jars per `classpathDependencyExcludes` den Platz auf dem
  Test-Classpath weg und startet den Kontext ohne sie; `AbwaehlbareModuleXhtmlTest` deckt den
  Weg ab, den der Kontext-Start nicht sieht — eine Kern-XHTML, die eine Bean des Moduls anspricht.
* **Negativ:** Die Liste steht an drei Stellen (pom.xml, `SchlankerKontextTest`,
  `docs/MODULE_ABWAEHLEN.md`). Maven kann Plugin-Konfiguration nicht aus Java lesen; der Test
  prüft die Deckung gegen die pom, die Doku bleibt Handarbeit.
* **Negativ:** Wer ein Modul aus einer App wirft, deren Datenbank dessen Flyway-Migrationen schon
  angewandt hat, braucht `spring.flyway.validate-on-migrate: false` (root selbst setzt das seit
  dem Wegfall von `plaintext-root-email` ohnehin). Steht in der Anleitung.
* **Offen:** `plaintext-admin-cron` startet nachweislich auch ohne — die Abschaltung wäre aber
  **still**: `PlaintextCron`-Beans blieben gewöhnliche Beans und liefen nie wieder. Deshalb
  steht es nicht in der Liste. Wer es aufnehmen will, braucht zuerst einen lauten Hinweis beim
  Start (`PlaintextCron`-Bean ohne `CronBeanPostProcessor` → Warnung).
