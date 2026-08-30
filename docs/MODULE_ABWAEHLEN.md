# Admin-Module abwählen

`plaintext-root-webapp` ist ein Aggregator: eine App hängt an diesem einen Artefakt und bekommt
alle 20 internen Module. Wer ein Modul nicht braucht, wirft es per Maven-`<exclusions>` heraus.
Warum genau so und nicht per `optional`, BOM oder Property, steht in
[ADR 0007](adr/0007-admin-module-abwaehlbar-per-exclusions.md).

**Die Vorgabe bleibt „alles an".** Ohne Zutun ändert sich für eine App nichts.

## Welche Module abwählbar sind

| Modul | Abwählbar | Begründung |
|---|---|---|
| `plaintext-admin-webhooks` | **ja** | Kein Kern-Code und keine Kern-XHTML fasst das Modul an. |
| `plaintext-admin-notifications` | **ja** | Einzige Kern-Referenz ist das Glöckchen in `includes/topbar.xhtml`, und die steht unter `rendered="#{notificationTopbarBean != null}"`. Braucht `plaintext-admin-mailtemplate` (Mailversand) — das bleibt ohnehin. |
| `plaintext-admin-secrets` | **ja** | `SecretResolver` liegt in `-interfaces` und hat im Framework keinen Konsumenten. |
| `plaintext-admin-modules` | **ja** | `MenuItemImpl` holt den `ModuleEnablementProvider` über `beanFactory.getBean(…)` in einem try/catch; fehlt er, blendet das Menü nichts modulweise aus. Mit dem Modul entfällt die Seite „Module" und das DB-gestützte Abschalten — die Rollen-Variante `plaintext.menu.module-roles.<key>` bleibt (die sitzt im `ModuleRoleService` von `plaintext-root-menu`). |
| `plaintext-admin-cron` | **nein, bewusst** | Der Kontext startet ohne — aber `CronBeanPostProcessor` und `SuperCron` liegen in diesem Modul. Ohne sie bleibt jede `PlaintextCron`-Bean eine gewöhnliche Bean und läuft **nie wieder**, ohne Fehler und ohne Logzeile. Eine stille Abschaltung ist schlimmer als keine. |
| `plaintext-admin-i18n` | **nein** | Der Kontext startet zwar ohne, aber `#{i18n.t('…')}` steht in praktisch jeder Kern-XHTML (`login`, `myuser`, `useradmin`, `topbar`, …). Ohne das Modul rendert keine Seite mehr. Ein Fall, den der Kontext-Start allein nicht gezeigt hätte. |
| `plaintext-admin-settings` | **nein** | `LoginSetupBean` bindet `SetupConfig`/`SetupConfigService` direkt, `#{brandingBean}` steht in `footer.xhtml` und `topbar.xhtml`. |
| `plaintext-admin-oidc` | **nein** | `PlaintextOidcUserService`, `JdbcClientRegistrationRepository` und `LoginSetupBean` binden `OidcConfig`/`OidcConfigService` direkt; `login.xhtml` fragt ungeschützt `#{oidcConfigService.oidcEnabled}`. |
| `plaintext-admin-apitoken` | **nein** | `JwksController` (Kern) importiert `ch.plaintext.apitoken.JwtTokenService`. |
| `plaintext-admin-sessions` | **nein** | `PasswordResetService` nennt `ch.plaintext.sessions.service.HttpSessionRegistry` im Typ eines `ObjectProvider` — das lädt die Klasse, auch wenn die Bean optional ist. |
| `plaintext-admin-mailtemplate` | **nein** | `RegistrationService`, `PasswordResetService` und `MagicLinkService` bekommen `IMailTemplateProvider` per Konstruktor, ohne Ausweichpfad. Abwählbar wäre es erst mit einer Vorgabe-Aufbereitung im Kern — das ist eine Verhaltensänderung an drei sicherheitsnahen Mailwegen und gehört in eine eigene Karte. |
| `plaintext-admin-requirements` | **entfällt** | Steht gar nicht in `plaintext-root-webapp`; wer es will, deklariert es selbst (so macht es `plaintext-app`). |

## So wählt eine App ein Modul ab

```xml
<dependency>
    <groupId>ch.plaintext</groupId>
    <artifactId>plaintext-root-webapp</artifactId>
    <exclusions>
        <exclusion>
            <groupId>ch.plaintext</groupId>
            <artifactId>plaintext-admin-webhooks</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

Dann noch zwei Dinge prüfen:

1. **Flyway.** Die Migrationen des Moduls liegen in dessen Jar unter `classpath:db/migration` und
   sind mit ihm weg. Auf einer frischen Datenbank ist das folgenlos. Hat die Datenbank sie schon
   angewandt, meldet Flyway sie als „applied migration not resolved locally" — dagegen hilft
   `spring.flyway.validate-on-migrate: false` in der `application.yml` der App (root und
   plaintext-app fahren das ohnehin). Die Tabellen des Moduls bleiben stehen; sie zu löschen ist
   eine eigene, bewusste Migration.
2. **Eigener Code.** Greift die App selbst auf das Modul zu (eigene Backing-Bean, eigener
   Service), fällt sie mit `NoClassDefFoundError` um — der Ausschluss gilt für den ganzen
   Classpath, nicht nur für root.

Die Menü-Einträge des Moduls verschwinden von selbst: sie stecken in dessen eigenen
`@MenuAnnotation`-Klassen.

## Wie das abgesichert ist

Zwei Tests in `plaintext-root-webapp`, beide im normalen `mvn test`:

* **`SchlankerKontextTest`** läuft in der zweiten Surefire-Ausführung
  `kontext-ohne-abwaehlbare-module`. Die nimmt den vier Jars per `classpathDependencyExcludes`
  den Platz auf dem Test-Classpath weg — die Abwesenheit ist echt, nicht simuliert — und startet
  den Spring-Kontext gegen ein eingebettetes PostgreSQL. Geprüft wird: Kontext oben, Menü-Registry
  und Benutzer-Repository da, keine einzige Bean aus den vier Paketen, Flyway ohne offene
  Migration. Ein `spring.autoconfigure.exclude` hätte das **nicht** bewiesen: die Beans hängen in
  root wie in allen vier Apps am `@ComponentScan("ch.plaintext")`, nicht an der AutoConfiguration.
* **`AbwaehlbareModuleXhtmlTest`** schliesst die Lücke von der anderen Seite. Der Kontext-Start
  rendert keine Seite; eine Kern-XHTML mit `#{webhookBean.x}` fiele ihm nicht auf. Der Test sammelt
  die Bean-Namen der vier Module und verlangt für jede Erwähnung in einer XHTML aus
  `plaintext-root-webapp` oder `plaintext-root-template` einen Null-Schutz im selben Dokument.

**Wer die Liste ändert, ändert sie an drei Stellen:** `plaintext-root-webapp/pom.xml`
(`classpathDependencyExcludes` und die Systemproperty), `SchlankerKontextTest`,
`AbwaehlbareModuleXhtmlTest` — und diese Tabelle. Der Test vergleicht die Anzahl gegen die pom und
wird laut, wenn beide auseinanderlaufen.
