---
layout: default
title: Role Registry
---

# Role Registry (Modul-Rollen-Registrierung)

Module — in root selbst und in Apps, die root nutzen — deklarieren ihre Rollen als Spring-Bean.
Root sammelt alle Deklarationen ein (Union, dedupliziert) und bietet sie in der
**Benutzerverwaltung** (`useradmin.xhtml`) und der **Rollenzuteilung** als Auswahl an, statt dass
Rollennamen freihaendig getippt werden muessen.

Der Mechanismus folgt demselben Muster wie das [Menü-System](ARCHITECTURE.md#menu-system):
Module steuern ihren Beitrag als Bean bei, root sammelt zentral ein.

## Rollen deklarieren

Eine `PlaintextRoleProvider`-Bean pro Modul (Interface in `plaintext-root-common`,
`ch.plaintext.framework`):

```java
@Component
public class MeinModulRoleProvider implements PlaintextRoleProvider {

    @Override
    public Set<PlaintextRole> getDeclaredRoles() {
        Set<PlaintextRole> ret = new LinkedHashSet<>();
        ret.add(new PlaintextRole("postkonto", "Zugriff auf das Postkonto-Modul"));
        ret.add(new PlaintextRole("privatausgaben", "Erfassen und Auswerten privater Ausgaben"));
        return ret;
    }

    @Override
    public Set<String> getRoles() {
        return getDeclaredRoles().stream().map(PlaintextRole::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
```

Bestehende Provider, die nur `getRoles()` implementieren, funktionieren unveraendert weiter:
`getDeclaredRoles()` hat einen Default, der Rollen ohne Beschreibung ableitet.

Referenzimplementierung: `RootRoleProvider` (`plaintext-root-webapp`,
`ch.plaintext.boot.plugins.security`) deklariert die root-eigenen Rollen `root`, `admin`, `user`.
`ROLE_SYSTEM` wird bewusst nicht deklariert (rein technische Authority fuer Cron/Bus).

## Namens-Konventionen

| Form | Beispiel | Verwendung |
|------|----------|------------|
| `PlaintextRole.name()` | `admin` oder `ROLE_ADMIN` | wie deklariert |
| `PlaintextRole.normalizedName()` | `admin` | Identitaet/Dedup; Speicherformat am `MyUserEntity` |
| `PlaintextRole.authorityName()` | `ROLE_ADMIN` | Spring-Security-Authority / Rollenzuteilung |

Deklarationen sind case- und prefix-insensitiv dedupliziert: `admin`, `ADMIN` und `ROLE_admin`
sind dieselbe Rolle. Deklarieren mehrere Module dieselbe Rolle, gewinnt die erste nicht-leere
Beschreibung.

## Einsammeln (Konsumenten-Seite)

`PlaintextRoleRegistry` (`@Component`, `plaintext-root-common`) sammelt alle Provider-Beans ein:

| Methode | Liefert |
|---------|---------|
| `getDeclaredRoles()` | sortierte `PlaintextRole`-Liste (Name + Beschreibung) |
| `getDeclaredRoleNames()` | normalisierte Namen (`admin`, ...) |
| `getDeclaredAuthorityNames()` | Authority-Namen (`ROLE_ADMIN`, ...) |
| `getDescription(name)` | Beschreibung einer Rolle (beliebige Schreibweise) |

## Bestandsrollen gehen nicht verloren

Die Auswahl-UIs mischen zu den deklarierten Rollen immer den **Bestand** aus der Datenbank dazu
(am Benutzer gespeicherte bzw. in der Rollenzuteilung vergebene Rollen). Eine Rolle, die kein
Modul (mehr) deklariert, bleibt dadurch sichtbar und waehlbar — sie verliert nur ihre
Beschreibung.

---

# Konfigurierbare Modul-Rollen (`plaintext.menu.module-roles`)

*seit root 1.604.0*

Eine App kann einem **ganzen Modul** per Konfiguration eine Rolle zuordnen. Ohne diese Rolle ist
das Modul fuer den Benutzer nicht vorhanden:

* der **Menuepunkt** (und alle Untermenues) ist unsichtbar,
* die **Dashboard-Kachel** ist unsichtbar,
* der **Direktaufruf** der Seiten wird vom `PageAccessGuard` verweigert.

`admin` und `root` behalten **immer** Zugriff. Module ohne Eintrag verhalten sich exakt wie bisher.

## Warum Konfiguration und nicht Modul-Code

Dasselbe Modul-Artefakt wird von mehreren Apps gebuendelt — `plaintext-z-mailbox` laeuft in
`app.plaintext.ch` **und** in `app.guild42.ch`. Eine Rolle im Modul-Code waere in beiden Apps
dieselbe. Die Zuordnung gehoert deshalb in die Konfiguration der jeweiligen App; **am Modul wird
nichts geaendert**.

## Konfiguration

```yaml
plaintext:
  menu:
    module-roles:
      wiki: wiki            # nur wer ROLE_WIKI hat, sieht das Wiki
      mailbox: mail
      postkonto: finanzen   # mehrere Module duerfen sich eine Rolle teilen
      rechnungen: finanzen
      buchhaltung: finanzen
```

Der Rollenname wird ohne `ROLE_`-Prefix geschrieben; Gross-/Kleinschreibung ist egal
(`wiki`, `WIKI` und `ROLE_Wiki` sind dieselbe Rolle).

## Der Modul-Key

Primaer die **`moduleId`** — derselbe Wert wie in `@MenuAnnotation(moduleId = "…")` bzw.
`ModuleDescriptor#moduleId()`, also genau das, was das Admin-Panel **„Root | Module"** anzeigt.

Fuer Module ohne `moduleId` greift als Fallback die **Menu-Root-Id**: der `menuId` des obersten
Menuepunkts des Moduls, ersatzweise sein aus dem Titel abgeleiteter Bezeichner (kleingeschrieben,
Umlaute transliteriert, Sonderzeichen zu `_`). Beide Formen funktionieren — beim Modul
*Rechnungen* etwa sowohl `rechnungen` (moduleId) als auch `rechnungsverwaltung` (Menu-Root-Titel).

**Den Key eines Moduls herausfinden** — drei Wege:

1. Admin-Panel **„Root | Module"**: die Spalte *Modul-Id*.
2. Das Startup-Log der App:
   `ModuleRoleService : Modul-Rollen aktiv: {wiki=WIKI} — erkannte Modul-Keys: [anschreiben, buchhaltung, …]`
3. Im Quelltext: `moduleId` der `@MenuAnnotation` am Wurzelmenue des Moduls.

Ein konfigurierter Key, den kein Menuepunkt beansprucht, bricht den Start **nicht** — er wird beim
Start als WARN gemeldet (inklusive der Liste der bekannten Keys).

## Wie Menue, Kachel, PageGuard und Registry zusammenspielen

| Baustein | Wirkung |
|----------|---------|
| `ModuleRoleProperties` (`plaintext-root-interfaces`) | bindet `plaintext.menu.module-roles`, kanonisiert Keys/Rollen |
| `ModuleRoleService` (`plaintext-root-menu`) | leitet die Modul-Zugehoerigkeit jedes Menuepunkts aus `moduleId` + Elternkette ab und schreibt die geforderten Rollen an den Menuepunkt |
| `MenuItemImpl.isOn()` | prueft die Modul-Rolle als vierten Schritt — Menue **und** `PageAccessGuard` fragen beide diese Methode |
| `TileItemImpl.isOn()` | ordnet die Kachel ueber ihren `link` (ersatzweise `menuTitle`) dem Modul-Menue zu |
| `ModuleRoleDeclarationProvider` (`plaintext-root-common`) | meldet die konfigurierten Rollen automatisch an die `PlaintextRoleRegistry` |

Weil die Pruefung **in** `MenuItemImpl.isOn()` sitzt, greift sie auch dort, wo die Eltern-Vererbung
des Guards ausdruecklich nicht greift: Untermenues, die selbst `roles = {USER, ADMIN, ROOT}`
deklarieren (der Normalfall), sind sonst per Direkt-URL offen.

## Rolle vergeben

Die konfigurierten Rollen erscheinen **automatisch** in der Benutzerverwaltung — die App braucht
dafuer keinen eigenen `PlaintextRoleProvider`. Die Beschreibung wird generiert:

* eine Rolle fuer ein Modul → *„Zugriff auf das Modul wiki"*
* eine Rolle fuer mehrere Module → *„Zugriff auf die Module buchhaltung, postkonto, rechnungen"*

Deklariert die App fuer dieselbe Rolle bereits einen eigenen Provider (z.B. guild fuer `finanzen`),
gewinnt dessen ausfuehrlichere Beschreibung.

> **Nach dem Deploy:** Eine neu konfigurierte Modul-Rolle hat noch niemand. Bis sie in der
> Benutzerverwaltung vergeben wird, sehen nur `admin` und `root` das Modul.

---

# Zustaendigkeitsregel: root sagt WELCHE, admin sagt WER

*seit root 1.608.0*

Die Sichtbarkeit eines Menuepunkts hat **zwei Zustaendige**, und sie beantworten verschiedene
Fragen. Wer die beiden verwechselt, sucht den Fehler an der falschen Stelle.

| Frage | Wer | Werkzeug |
|-------|-----|----------|
| **Welche Module gehoeren zu diesem Mandanten?** | `root` | `Root → Menuesteuerung` (Mandanten-White-/Blacklist) |
| **Wer darf sie benutzen?** | `admin` | Modul-Rollen an Benutzer vergeben (Benutzerverwaltung, Rollenzuteilung) |

Die vier Filter in `MenuItemImpl.isOn()` bleiben **UND-verknuepft**: eine Rolle hebt den
Mandantenfilter **nicht** auf. Das ist Absicht — der Mandantenfilter beschreibt den Zuschnitt eines
Mandanten, nicht ein Recht.

## Die vier Filter im Ueberblick

| # | Filter | Quelle | Bypass |
|---|--------|--------|--------|
| 1 | Rolle | `@MenuAnnotation(roles = …)` | — |
| 2 | Modul-Rolle | `plaintext.menu.module-roles` | `admin`, `root` |
| 3 | Modul aktiv | `Root → Module` | — |
| 4 | Mandant | Mandanten-White-/Blacklist | **nur** der Root-Zweig fuer `root` |

## Die eine Ausnahme: der Root-Zweig

Der Mandantenfilter kennt genau **eine** Ausnahme, und sie ist so eng gefasst wie moeglich:

```java
// MenuItemImpl
public boolean isRootBranchExemptFromMandate() {
    return isUnderRootMenu() && securityProvider != null && securityProvider.hasRole(ROLE_ROOT);
}
```

Beide Bedingungen muessen zutreffen: der Menuepunkt liegt im **Root-Zweig** (Titel `Root` oder
Elternmenue `Root`) **und** der Benutzer haelt `ROLE_ROOT`.

**Warum es sie gibt:** Die Menuesteuerung selbst (`Root | Menuesteuerung`) haengt im Root-Zweig.
Steht ein Mandant im Whitelist-Modus ohne diesen Titel, sperrt sich root aus der einzigen
Oberflaeche aus, mit der die Liste zu korrigieren waere — per Menue *und* per Direkt-URL, weil der
`PageAccessGuard` dieselbe `isOn()` auswertet. Erhoben auf app.plaintext.ch: in **8 von 10**
Mandanten war die Menuesteuerung so unerreichbar.

**Was sie nicht ist:** kein genereller Bypass. Ausserhalb des Root-Zweigs bleibt der Mandantenfilter
auch fuer root scharf, und die drei anderen Filter bleiben ueberall unangetastet — ein deaktiviertes
Modul bleibt auch im Root-Zweig deaktiviert.

## Mandanten-Listen: zwei Formen von Eintraegen

| Form | Beispiel | Wirkung |
|------|----------|---------|
| **Modul** | `modul:wiki` | das ganze Modul samt allen Untermenues |
| **Menue-Titel** | `Wiki \| Projekte` | genau dieser eine Menuepunkt |

Der Modul-Key ist **derselbe**, den auch die Modul-Rollen benutzen (`moduleId`, ersatzweise die
Menu-Root-Id). Damit sprechen beide Zustaendigen dasselbe Vokabular: `ModuleRoleService.resolve()`
schreibt die Keys als `MenuItemImpl.moduleKeys` an jeden Menuepunkt, und `MenuItemImpl` reicht sie
ueber `MenuVisibilityProvider.isMenuVisible(titel, moduleKeys)` an die Mandanten-Liste weiter.

**Warum der Praefix `modul:` und nicht die nackte `moduleId`** — die Umstellung muss verlustfrei
sein. Eine nackte `moduleId` waere mehrdeutig: ein Modul mit der Id `wiki` hat sein Wurzelmenue in
aller Regel unter dem Titel `Wiki`, und im Bestand stehen zusaetzlich Eintraege, die auf keinen
Titel mehr passen (Umbenennungen — auf app.plaintext.ch 19 von 123). Ein nackter Key waere damit
mal Titel, mal Modul, je nach Zustand des Menuebaums. Mit dem Praefix ist die Absicht am Eintrag
selbst ablesbar: **alles ohne Praefix ist und bleibt ein Titel**, und weil kein Menue-Titel mit
`modul:` beginnt, kann kein Bestandseintrag seine Bedeutung aendern.

Die Detailseite fuehrt drei getrennte Auswahlen — Module, Menuepunkte und **Eintraege ohne
Entsprechung im Menuebaum**. Die dritte Gruppe ist vorausgewaehlt: Speichern wirft nichts weg, was
der Bearbeiter nicht bewusst abgewaehlt hat.

## Tote Listen-Eintraege

Beim Start meldet `MandateMenuStartupReport` pro Mandant, welche Eintraege im aktuellen Menuebaum
ins Leere zeigen — analog zur bestehenden Meldung `ModuleRoleService: Modul-Rolle konfiguriert fuer
unbekannten Modul-Key`:

```
WARN  Menuesteuerung: Mandant 'lauftage2026' (Whitelist-Modus) hat 3 Listen-Eintraege ohne
      Entsprechung im Menuebaum — sie wirken nicht (umbenannter Menuepunkt?): [...]
```

Wirkung eines toten Eintrags: im **Blacklist**-Modus blendet er nichts mehr aus; im
**Whitelist**-Modus blendet er den umbenannten Punkt still **aus**.

## Diagnose-Ansicht

`Root → Menue-Diagnose` (`menudiagnose.html`, nur ROOT) zeigt jeden Menuepunkt mit den vier Filtern
als eigene Spalten und nennt zu jedem *Nein* den Grund („Rolle WIKI fehlt", „nicht in Whitelist von
lauftage2026", „Modul 'wiki' ist deaktiviert").

Die Ja/Nein-Werte kommen aus **denselben** Methoden, die auch Menue und `PageAccessGuard` benutzen
(`MenuItemImpl.isRoleVisible()`, `isModuleRoleVisible()`, `isModuleVisible()`, `isMandateVisible()`
— dafuer oeffentlich). `MenuDiagnoseService` bildet nur den Text dazu; eine zweite Kopie der Logik
wuerde abdriften.

Im **Impersonate**-Modus zeigt die Tabelle die Sicht des impersonierten Benutzers, ohne Zutun: die
Menuepunkte befragen den `SecurityProvider` der laufenden Session, und die Impersonation tauscht
genau dort die `Authentication` aus.

## Welche Rollen darf admin vergeben

`PrivilegedRoleRules` (`plaintext-root-common`, `ch.plaintext.framework`) ist die eine Stelle, an
der das entschieden wird — benutzt von der Benutzerverwaltung **und** der Rollenzuteilung.

| Rolle | admin darf neu vergeben | Grund |
|-------|-------------------------|-------|
| Modul-Rollen (`wiki`, `finanzen`, …) | **ja** | reine Zugangsrollen zu einem Fachmodul — genau admins Aufgabe |
| `user` | ja | keine Verwaltungsrechte |
| `admin` | nein | admin koennte sonst seine eigene Beschraenkung aufheben |
| `root` | nein | Verwaltungsrechte |
| `PROPERTY_*` | nein | wirkt ueber den eigenen Mandanten hinaus (Mandanten-Wechsel) |

**Bestand bleibt unangetastet:** die Regel gilt nur fuer das NEU-Vergeben. Eine bereits
persistierte Zuweisung bleibt bestehen und editierbar; beide Aufrufer pruefen deshalb gegen den
persistierten Stand, nicht gegen das Formular.

Die Rollenzuteilung (`Admin → Rollenzuteilung`) braucht dieselbe Pruefung wie die
Benutzerverwaltung — sonst waere sie der Umweg, auf dem ein admin sich `ROLE_ROOT` zuteilt.

## Mandanten entfernen

`Root → Mandate → Aus Verwaltung entfernen` loescht **nur, was das Framework besitzt**: die
`mandate_menu_config` des Mandanten und damit seinen Eintrag in der Auswahlliste. Die Fachdaten der
Anwendung bleiben bestehen — das Framework kennt diese Tabellen nicht (in plaintext-app liegen sie
ueber acht Tabellen verteilt). Vorher entfernte die Aktion den Mandanten nur aus einer `ArrayList`
in der Session und meldete trotzdem Erfolg; nach einem `reload()` war er wieder da.

Zwei Fallen beim Suchen nach Mandantendaten:

1. **Mandantenzugehoerigkeit gibt es zweimal** — als Rollen-Property `PROPERTY_MANDAT_<NAME>` am
   Benutzer (Heimat-Mandant) und in der Tabelle `user_mandate` (Zusatz-Mandate). Wer nur die erste
   prueft, meldet „keine Benutzer betroffen", obwohl welche zugeordnet sind.
2. **Mandantennamen sind nicht case-konsistent** — in `user_session` stand `BUTSCHER` gross,
   waehrend derselbe Mandant ueberall sonst `butscher` klein geschrieben ist. Immer
   case-insensitiv vergleichen (`findByMandatIgnoreCase`, `findByMandateNameIgnoreCase`).

## Anleitungsseiten in der Anwendung

| Seite | Fuer | Inhalt |
|-------|------|--------|
| `Root → Anleitung Menuesteuerung` | ROOT | White-/Blacklist, wann welcher Modus, Modul- vs. Titel-Eintrag, Diagnose lesen, Impersonate, Mandanten anlegen/entfernen |
| `Admin → Anleitung Modul-Rollen` | ADMIN, ROOT | Modul-Rollen vergeben, was sie bewirken, Zusammenspiel mit den Mandanten-Listen, wann man root braucht |
