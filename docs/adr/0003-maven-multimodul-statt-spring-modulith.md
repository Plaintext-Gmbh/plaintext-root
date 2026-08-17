# Maven-Multimodul beibehalten, Spring Modulith punktuell zukaufen

* **Status:** proposed
* **Date:** 2026-08-17
* **Deciders:** Daniel
* **Consulted:** worker01 (Analyse)
* **Informed:** alle Bearbeiter

## Context

`plaintext-root` ist heute ein **Maven-Reactor mit 24 Modulen** (693 Java-Dateien, ~93 000
Zeilen). Die Frage war, ob der Umstieg auf **Spring Modulith 2.1** den Ansatz verbessert.

Der entscheidende Umstand vorweg, weil er die meisten Argumente umdreht: **Die Module von
`plaintext-root` sind keine internen Pakete einer Anwendung, sondern veröffentlichte Bibliotheken.**
Gemessen an den Konsumenten-POMs:

```
plaintext-app       16 verschiedene root-/admin-Artefakte
plaintext-guild     15
plaintext-schuetu    8
```

Spring Modulith adressiert den umgekehrten Fall: **einen** Deployment-Monolithen, dessen logische
Teile man sauber halten will, damit sie später Services werden können. Fünf Anwendungen, die sich
Module über einen Maven-Repository teilen, sind kein Modulith-Szenario — sie sind schon einen
Schritt weiter.

## Decision

Wir bleiben beim Maven-Multimodul und übernehmen aus Spring Modulith **einzelne Bausteine dort, wo
sie eine belegte Lücke schliessen** — nicht die Modul-Definition, sondern die Werkzeuge um sie
herum. Konkret in dieser Reihenfolge:

1. **`spring-modulith-docs`** für die Architektur-Dokumentation (siehe Lücke unten).
2. **`spring-modulith-events` / Event Publication Registry** prüfen, wenn ein Modul-Ereignis
   verlorengehen darf, aber nicht soll (heute 8 Publisher- und 8 Listener-Dateien).
3. **`ApplicationModules.verify()`** nur, falls einzelne Module intern in Pakete wachsen, deren
   Grenzen der Compiler nicht mehr abbildet.

Kein Umbau der Modulstruktur, kein Zusammenlegen von Maven-Modulen.

## Consequences

* **Positiv:** Die Compile-Zeit-Grenze bleibt. Was in einem Maven-Modul nicht als Abhängigkeit
  steht, lässt sich nicht importieren — das ist stärker als jede Testregel, weil es keinen Weg
  daran vorbei gibt. Modulith prüft Grenzen erst im Test; ein übersprungener Test ist eine offene
  Grenze.
* **Positiv:** Die Wiederverwendung über fünf Anwendungen bleibt unangetastet. Ein Modulith-Umbau
  müsste sie über `@Modulithic(additionalPackages)` oder `ApplicationModuleSourceFactory`
  nachbauen — mehr Mechanik für dasselbe Ergebnis.
* **Positiv:** `plaintext-root-interfaces` (48 Verträge) ist bereits das, was Modulith „Named
  Interface" nennt — nur als eigenes Artefakt statt als Paketkonvention. Der Unterschied ist die
  Durchsetzung: hier hart, dort per Test.
* **Negativ:** Der Reactor-Overhead bleibt. 24 Module heissen 24 Jar-Bauschritte, und ein
  Modulwechsel zieht einen Release durch die Konsumenten (Auto-Bump). Modulith hätte einen
  einzigen Bauschritt.
* **Negativ:** Wir bekommen die Modulith-Zugaben nicht geschenkt: generierte Dokumentation,
  Modul-Traces in der Observability, `Moments`-API. Was wir davon wollen, müssen wir einzeln
  einbauen (siehe Decision).
* **Neutral:** Die 42 Stellen mit `@Autowired(required = false)` sind unser Muster für „Modul kann
  fehlen". Modulith kennt dafür kein Gegenstück — es geht von einem festen Satz Module aus. Das
  ist ein Argument für uns, aber es macht die optionale Verdrahtung nicht schöner.

## Die eine Lücke, die Modulith heute schliessen würde

**`docs/ARCHITECTURE.md` ist von Hand gepflegt und nicht mehr deckungsgleich mit dem Code.**
Gemessen am 17.08.2026:

```
Maven-Module im Verzeichnis                        24
Kästen im Modul-Diagramm                           16

im Diagramm, aber KEIN Modul dazu                  Email Module, Wertelisten, Filelist
Modul vorhanden, im Diagramm nicht zu finden       apitoken, i18n, modules, oidc, secrets,
                                                   webhooks, pageguard, archtests
```

(Zwei weitere Kästen tragen deutsche Namen für englisch benannte Module — `Menuesteuerung` →
`menu-visibility`, `Rollenzuteilung` → `role-assignment`; das ist Lesbarkeit, kein Fehler.)

Ein Diagramm, das drei Module nennt, die es nicht gibt, und acht verschweigt, ist keine
Dokumentation mehr, sondern eine Behauptung. Genau hier ist `spring-modulith-docs` stark: es
erzeugt die Übersicht **aus dem Code**, womit sie nicht mehr veralten kann.

## Alternatives considered

**Vollumstieg auf Spring Modulith.** Verworfen: Die 24 Maven-Module wären ein einziges, die
Grenzen wanderten von der Compile-Zeit in einen Test, und die Wiederverwendung in vier
Konsumenten-Anwendungen müsste über `additionalPackages` nachgebaut werden. Wir gäben eine harte
Grenze für eine geprüfte auf und bekämen dafür Werkzeuge, die auch einzeln zu haben sind.

**Beides parallel: Modulith über den bestehenden Modulen.** Nicht verworfen, aber ungeprüft — die
Dokumentation nennt Mono-Repo-Strukturen als Standardfall und behandelt Module über mehrere Jars
nicht als solchen. Wer es versucht, sollte mit `spring-modulith-docs` an **einem** Konsumenten
(z. B. `plaintext-schuetu`, 8 Artefakte) anfangen und messen, ob die erzeugte Übersicht die
Modulgrenzen überhaupt erkennt. Das ist der billigste belastbare Versuch.

**Nur die Dokumentation von Hand nachziehen.** Möglich und heute nötig, aber es behebt nur den
aktuellen Stand: dasselbe Auseinanderlaufen beginnt mit dem nächsten Modul von neuem.

## Was diese Analyse NICHT geprüft hat

* **Ob `ApplicationModules.verify()` über Maven-Modul-Grenzen hinweg überhaupt etwas findet.** Die
  Referenz beschreibt Paketkonventionen innerhalb einer Anwendung; für unseren Aufbau ist das eine
  offene Frage, nicht eine beantwortete.
* **Die Bauzeit.** Dass 24 Reactor-Module langsamer sind als ein Modul, ist plausibel, aber hier
  nicht gemessen — und der Vergleich wäre unfair, solange die Konsumenten dieselben Artefakte
  brauchen.
* **Ob die 8 Publisher/8 Listener wirklich eine Registry brauchen.** Das entscheidet sich pro
  Ereignis daran, ob sein Verlust auffiele. Diese Frage ist nicht mit einer Bibliothek zu
  beantworten.
