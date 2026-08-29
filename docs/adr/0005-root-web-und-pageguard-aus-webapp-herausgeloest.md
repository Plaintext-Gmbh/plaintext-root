# `plaintext-root-web` und `plaintext-root-pageguard` aus `plaintext-root-webapp` herausgelöst

* **Status:** accepted
* **Date:** 2026-08-29 (nachträglich festgehalten; umgesetzt in der Release-Linie 1.635.0)
* **Deciders:** Daniel Marthaler

## Context

`plaintext-root-webapp` ist ein **Anwendungs**-Modul: eigene `application.yml`, eigene
`SecurityFilterChain`, eigene JPA-Entities, und es zieht achtzehn `ch.plaintext`-Module plus
Flyway und PostgreSQL. Trotzdem lagen dort Dinge, die jede Konsumenten-App braucht, auch
wenn sie nicht die ganze root-App will: der `.html`/`.htm` → `.xhtml`-Rewrite-Filter, der
Spring-Security-gestützte `SecurityProvider`, `MenuBean`, und der komplette Page Access
Guard. Eine App, die nur das URL-Rewriting oder nur den Seitenschutz wollte, musste alles
nehmen.

Dazu kam ein zweites Problem: diese Klassen waren `@Component`/`@Service` und nur über
Component-Scan von `ch.plaintext` erreichbar. Eine App, die das Paket nicht scannte,
startete sauber — und hatte **still** keinen Guard und kein Rewriting (jeder `.html`-Link
404). Fail-open ohne Fehlermeldung.

## Decision

Zwei neue Module, beide über `AutoConfiguration.imports` registriert statt über
Component-Scan:

* **`plaintext-root-web`**: `UrlRewriteConfig`, `SpringSecurityProvider`, `MenuBean`,
  `SessionTrackingConfig`, die Debug-Controller. `WebAutoConfiguration` ist
  `@AutoConfigureBefore(MenuAutoConfiguration)`, damit der echte `SecurityProvider` den
  permissiven Default des Menü-Moduls schlägt (`SecurityProviderReihenfolgeTest`).
  `MenuBean` behält sein `@Component`, weil es im JSF-`view`-Scope lebt, den nur eine App mit
  JoinFaces kennt.
* **`plaintext-root-pageguard`**: `PageAccessGuardFilter`, Service, Startup-Report,
  `preRenderView`-Bean, `PageGuardMode`; `PageGuardAutoConfiguration`; publiziert ein
  `test-jar` mit `PageAccessGuardTestFactory`, damit eine App ihre eigenen Views gegen ihre
  eigenen Menüs prüfen kann.

Apps auf `plaintext-root-webapp` sind nicht betroffen — beide Module kommen transitiv, der
Filter wird weiterhin von `PlaintextSecurityConfig` in die Security-Kette gehängt.

## Consequences

* **Positiv:** Ein Konsument kann Seitenschutz und URL-Rewriting einzeln nehmen; die Module
  sind mit `@ConditionalOnWebApplication` / `@ConditionalOnMissingBean` abgesichert.
* **Positiv:** Kein stilles Fail-open mehr durch fehlenden Component-Scan — die
  Auto-Configuration greift ohne Zutun der App.
* **Negativ:** Zwei Reactor-Module mehr (24 statt 22); jede Modulgrenze ist ein weiterer
  Jar-Bauschritt und ein weiterer Eintrag in den Konsumenten-POMs (siehe ADR 0003 zum Preis
  des Multimoduls).
* **Negativ:** Die Bean-Reihenfolge ist jetzt eine Abhängigkeit zwischen Modulen; wer sie
  anfasst, riskiert den permissiven `SecurityProvider` als Gewinner — jeder Menüpunkt
  sichtbar, jede Seite erreichbar. Der Test pinnt das, mehr nicht.
* **Neutral:** `PlaintextSecurityConfig` bleibt in der webapp und kennt beide Module; die
  Position des Filters in der Kette ist dort dokumentiert.

## Alternatives considered

| Option | Why not? |
| --- | --- |
| Alles in `plaintext-root-webapp` lassen | Wer Seitenschutz will, bekommt die ganze App samt Entities und Flyway — das war der Ausgangspunkt. |
| Ein einziges Modul `plaintext-root-web` für beides | Der Guard ist ohne JSF-Template sinnvoll, das Rewriting ohne Guard; getrennt lassen sich beide einzeln konsumieren. |
| Component-Scan von `ch.plaintext` in jeder App vorschreiben | Behebt das Fail-open nur per Konvention; die Auto-Configuration behebt es im Framework. |

## References

* CHANGELOG 1.635.0, Abschnitte «Added» (`plaintext-root-web`, `plaintext-root-pageguard`,
  `PageGuardAutoConfiguration`) und «Fixed» (`WebAutoConfiguration`-Reihenfolge)
* ADR 0003 (Maven-Multimodul), ADR 0004 (Guard-Modi)
* `docs/MODULE_REFERENCE.md`
