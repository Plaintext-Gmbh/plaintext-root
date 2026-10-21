# UI 2 — die Grundlage in root (Karte 1548)

Entscheid Daniel, 15.10.2026: fünf Prinzipien für **neue** Seiten in app, guild, schuetu und iot.
root liefert die Bausteine; der Pilot (Gear in app) folgt in Karte 1549, öffentliche Seiten ohne
PrimeFaces in Karte 1550. Die lebende Referenz ist `/styleguide.html` (Admin → Styleguide), die
Bildschirmfotos (hell/dunkel, 1440/390) liegen in `docs/ui/1548/`.

| Prinzip | Baustein | Stand |
|---|---|---|
| 1 Ein Seitenkopf | `pt:seitenkopf titel untertitel mehr` mit `ui:define name="aktion"` / `"mehr"` | fertig |
| 2 Tabellen | `pt:tabelle` (Paginator ab Seite 2, `leerText`/`leerWeg`), `pt:colAmount` mit `tabular-nums`, Zeilenaktionen `styleClass="pt-zeilenaktionen"` | fertig |
| 3 Eine Sprache, ein Meldungsort | ein `globalGrowl` + ein `p:confirmDialog` im Template, deutsches Locale (beides Karte 1547); Lint gegen eigenes `p:growl` | fertig |
| 4 Tokens | `plaintext-layout/css/layout.css`: Fläche, Text, Akzent, Status; `--pt-abstand-1..4`; `--pt-radius`; eine Schrift in drei Größen; dunkel als zweiter Variablensatz; `pt:chip` | Tokens fertig; `layout-light/dark.css` bleiben vorerst |
| 5 Mobil | `pt:tabelle` als Karten (`pt-mobil-aus`), `pt:filter`, `.pt-nur-gross`, Seitenkopf mit einer Aktion | fertig |

## Eine neue Seite

```xml
<h:form id="fm">
    <input type="hidden" name="_csrf" value="#{_csrf.token}"/>
    <pt:seitenkopf titel="Verträge">
        <ui:define name="aktion">
            <p:commandButton value="Neu" icon="pi pi-plus" action="#{vertragBean.neu}"/>
        </ui:define>
    </pt:seitenkopf>
    <pt:filter>
        <p:inputText value="#{vertragBean.suche}" placeholder="Suche"/>
    </pt:filter>
    <pt:tabelle value="#{vertragBean.liste}" leerText="Noch keine Verträge."
                leerWeg="Mit «Neu» legen Sie den ersten an.">
        <pt:colText header="Nummer" value="#{row.nummer}"/>
        <pt:colText header="Kunde" value="#{row.kunde}"/>
        <pt:colText header="Ort" value="#{row.ort}" colClass="pt-mobil-aus"/>
        <p:column headerText="Status"><pt:chip text="#{row.status}" art="ok"/></p:column>
        <pt:colAmount header="Betrag" value="#{row.betrag}" currency="CHF" colClass="pt-mobil-aus"/>
    </pt:tabelle>
</h:form>
```

Regeln, die der Lint hält (`PlaintextUiGrundlageLinterTest`): kein `style="…"`, kein eigenes
`p:growl`, `_csrf` in jedem `h:form`. Der Bestand steht je Datei in `plaintext-ui-bestand.txt` und
darf nur sinken.

## Farbvarianten

Drei statt zehn: **Grün** (Vorgabe neuer Benutzer), **Blau** (Rückfall für Unbekanntes) und
**Orange** (die warme Seite). Gestrichene fallen nach Farbton auf die nächstliegende:
Türkis/Violett → Blau, Avocado/Limette → Grün, Rot/Karmin/Gelb → Orange. Eigene Farben bleiben.

## Für die Anwendungen beim root-Bump

- Nichts muss geändert werden; Bestandsseiten sehen gleich aus.
- Wer will, schaltet den Lint scharf: einmal
  `mvn test -Dtest=PlaintextUiGrundlageLinterTest -Dsurefire.failIfNoSpecifiedTests=false -Dplaintext.arch.ui-grundlage=bestand-schreiben`
  im webapp-Modul, `plaintext-ui-bestand.txt` committen, dann `plaintext.arch.ui-grundlage=enforce`
  in den `systemPropertyVariables` (wie in `plaintext-root-webapp/pom.xml`).
- Eine eigene Template-Kopie (Oblique) bekommt `layout.css`, `ui-grundlage.js` und die vier neuen
  Tags erst, wenn sie nachgezogen wird; bis dahin funktionieren nur die neuen Tags dort nicht.

## Offen (Folgekarten)

- `layout-light.css`/`layout-dark.css` auf die Tokens zurückführen und zu einer Datei machen
  (berührt jede Bestandsseite und die Oblique-Kopie — eigener Schritt mit Sichtprüfung je App).
- Bestand abbauen: 786 `style="` in 51 root-Dateien (Stand 21.10.2026).
- Kontrast Orange: `#FF9800` mit weißer Schrift liegt unter 4.5:1 — Wert für Knöpfe prüfen.
