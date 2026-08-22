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
