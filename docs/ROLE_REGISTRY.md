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
