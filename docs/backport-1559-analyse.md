# Karte 1559: Backport Draw.io, Präsentation und Files aus dem Inventar — Analyse

Stand 21.10.2026. Grundlage: `coreit-arch-inventar-app` github/master (`6d3bbe05`, Paket
`praesentation` vorhanden, Spiegel also aktuell genug), `plaintext-app` master, `plaintext-root` master.
Der Spiegel liegt laut Auftrag 163 Commits hinter Bitbucket; was danach im Inventar dazukam, ist hier nicht drin.

Die Analyse gehört zu vier PRs:

| Teilkarte | Repo | Inhalt |
|---|---|---|
| A | plaintext-root | Port `LinkTokenStore` + JPA-Standard (dieser PR) |
| B | plaintext-root | Modul `plaintext-root-files`, Port `DateiSpeicher` (auf A gestapelt) |
| C | plaintext-app | Draw.io nach Inventar-Verhalten, Port `DiagrammAblage` |
| D | plaintext-app | Präsentation nach Inventar-Verhalten, SPI `FoliensatzQuelle` (auf C gestapelt) |

## 1. Was es schon gibt — und warum das die Planung ändert

Die Annahme im Auftrag, plaintext-app habe «eine eigene Draw.io-Umsetzung» und «eine eigene Präsentation», stimmt,
unterschätzt aber, wie weit beide schon in Richtung Inventar gebaut sind:

- **plaintext-root hat schon einen Token-Mechanismus**: `plaintext-root-freigabe` (Karte 1476, Freigabe-Links
  ohne Anmeldung, Tabelle `freigabe_link`, Token nur als SHA-256, Ablauf, Widerruf, Zähler, MCP). Er ist laut
  Javadoc ausdrücklich aus `DrawioLinkService`/`PraesentationLinkService` der App entstanden. Draw.io und
  Präsentation der App benutzen ihn heute schon (`DrawioFreigabeQuelle`, `PraesentationFreigabeQuelle`,
  Migrationen `V1791693144`/`V1791693145` haben die alten Link-Tabellen dorthin kopiert).
- **plaintext-root hat schon einen Datei-Port**: `ch.plaintext.ablagen.DateiAblage` (Karte 1406/1475, Nextcloud,
  Git, Ordner anlegen/verschieben/löschen), implementiert in `plaintext-admin-sidecars`.
- **plaintext-app-Draw.io hat schon einen Port fürs Inventar**: `ch.plaintext.drawio.DiagrammSpeicher`, laut
  Javadoc «im Inventar ein REST-Client auf den Git-Dienst».
- **plaintext-app-Präsentation** hält im pom fest: nur root und app-interfaces, «damit sich das Modul ins Inventar
  zurückportieren lässt».

Folge für die Umsetzung:

1. **Kein dritter Token-Mechanismus.** `LinkTokenStore` (A) ist kein neues System neben der Freigabe, sondern ein
   schmaler Port, dessen Standard-Umsetzung **dieselbe Tabelle** `freigabe_link` nutzt (gleiches Token-Format,
   gleicher Hash, gleicher Zähler). Er unterscheidet sich von `FreigabeQuelle` nur darin, dass das Objekt ein
   Text-Schlüssel ist (Dateipfad, Diashow-Titel, Dateiname) statt einer DB-Id, und dass er sich als Bean ersetzen
   lässt — das braucht das Inventar, dessen Tokens als Konfigliste in cie-meta liegen (`DrawioLinkTokens`,
   `DiashowLinkTokens`, `FilesTokens` mit `TokenAufrufe`).
2. **`DateiSpeicher` (B)** ist bewusst kleiner als `DateiAblage`: Files braucht nur upload/download/exists/list/
   delete und ein Flag, ob der Speicher selbst aufräumt (im Inventar löscht der Code-Service nach 3 Wochen).
3. **C und D sind Zusammenführungen, keine Neubauten.** Die App-Module haben Funktionen, die das Inventar nicht hat
   (DB-Metadaten mit Mandat, mehrere Ablagen mit Rollen, Bildexport ohne Browser, Generator, Wiki-Einbettung,
   editierbare Text-/Bildfolien, PDF-Export). Die dürfen nach Regel 6 nicht wegfallen.

## 2. Files (Inventar `ch.admin.coreit.inventar.files`, 9 Klassen) → plaintext-root

| Inventar | Aufgabe | Kopplung → Port/Property |
|---|---|---|
| `FilesService` | persönliche Ablage `files/<benutzer>/<name>`, Frist 21 Tage nach letzter Änderung, Links | `storage.FileStorageService` → **`DateiSpeicher`**; `MetaListStore` `FilesTokens` + `TokenAufrufe` → **`LinkTokenStore`** (art `files`); `Umgebung`/`ICodeConfig` (BIT-URLs) → `EigeneAdresse` bzw. Property `plaintext.files.link-basis` |
| `Datei`, `FreigabeLink` | Records | ins Modul (`FreigabeLink` wird `LinkToken`) |
| `web/FilesApiController` | `/api/files/{dateien,hochladen,loeschen,datei,…}` im Format von `ablage.js` | Pfad modul-präfixiert, PageGuard über `PageAccessGuardService` wie gehabt |
| `web/FilesLinkBean` | Dialog «Link teilen» | FacesMessages |
| `web/FilesLinkController` | `GET /nosec/files/{token}` | bleibt so (bestehende Links!) |
| `web/FilesMcpController` | MCP-REST `list_files`, `get_file`, `save_file`, `create_file_link` | Identitätsfrage «nur Daniel Marthaler» ist Inventar-Spezifik → Property `plaintext.files.mcp-identitaet` (leer = aus) |
| `menu/FilesMenu` | Menü unter «Marthaler» | `InventarModulSichtbarkeit` → Menüplatz per Property/`moduleId` |

Tests: `FilesServiceTest` (4) und `FilesMcpControllerTest` (2) werden portiert.

Aufbewahrung: Im Inventar löscht der Code-Service. Port-Flag `DateiSpeicher#raeumtSelbstAuf()`; ist es `false`
(Dateisystem-Standard), räumt ein Cron des Moduls nach `plaintext.files.frist` (Vorgabe 21 Tage) auf.

## 3. Draw.io (Inventar `ch.admin.coreit.inventar.drawio`, 21 Klassen) ↔ `plaintext-z-drawio` (38 + 26 Testdateien)

### Inventar-Klassen und ihre Kopplungen

| Klasse | Aufgabe | externe Kopplung |
|---|---|---|
| `DrawioService`/`Impl` | Ablage nach Pfad in `cie-meta/drawio/`, Ordner über `.ordner`, `diashow.yml`, Änderungsdaten, `bibliotheken/` gesperrt, Event `DrawioUmzug` | `CodeService`, `BitbucketProxyService`, `ICodeConfig`, `Umgebung`, `Diashows` → **`DiagrammAblage`** |
| `DrawioSchreiber` | asynchron sammeln, ein Commit je Lauf | `CodeService` → Sache des Adapters |
| `DrawioLinkService` | Lese-/Schreib-Links, Ablauf, Zähler, Umzug | `MetaListStore`, `TokenAufrufe` → **`LinkTokenStore`** (art `drawio`) |
| `DrawioBibliothek` | Formenbibliothek, 5 min Cache, Vorgabe anlegen | Core-IT-Bibliothek ist Inventar-Inhalt; Mechanik ins Modul |
| `DrawioWarmup`, `DrawioWarmupIncoming` | Cache vorwärmen nach Start / Nacht-Kette | `AsyncRunner`, `Incoming` → Start-Warmup im Modul, Nacht-Hook im Adapter |
| `DrawioUmzug` | Ereignis umbenannt/gelöscht | ins Modul |
| `web/DrawioBackingBean`, `drawio.xhtml` | Liste, Ordnerbaum, Filter, `?auswahl=`, Upload/Download, Link-Dialog | `DiagrammDatei` → ins Modul |
| `web/DrawioDateienApiController` | `/api/drawio/*` für `ablage.js` | App hat `/drawio/api/ablage/{ablage}/*` |
| `web/DrawioEditorApiController` | `GET/POST /api/drawio/diagram`, `/nosec/drawio/diagramm` (Schreib-Token), Konflikt per SHA-256 | — |
| `web/DrawioEditorController` | `/drawio-editor/start`, Bibliothek | `AusserhalbDerApiLayer` (Inventar-Annotation) |
| `web/DrawioBildController`, `DrawioBildSeite`, `DrawioVorschauCache` | `drawio.html?diagramm=` = nur das Bild, Zoom, Seitenwahl, Browser-PNG-Cache | — |
| `web/DrawioLinkAnsichtController` | `/nosec/drawio/ansicht?diagramm=&token=` | — |
| `web/DrawioLinkMcpController` | MCP-REST Links, Ordner, Verschieben | Registrierung beim Inventar-MCP → Adapter |
| `web/DrawioSeitenZugriff`, `menu/DrawioMenu` | PageGuard, Admin darf bearbeiten; Menü unter «Marthaler» | `InventarModulSichtbarkeit` → Plaintext-Menü-Sichtbarkeit |

Ausserhalb des Pakets: `webui/plugins/security/DrawioTokenLinkFilter` (leitet `/drawio.html?diagramm=` auf die
Bildansicht), MCP-Endpunkte in `mcp/McpApiController`, `webui/tokens/TokenUebersicht` (token-links.xhtml).

### Vergleich

| Thema | Inventar | plaintext-app | Entscheid für C |
|---|---|---|---|
| Schlüssel | Pfad (`ordner/plan.drawio`) | DB-Id, Datei `<id>-<kurz>.drawio` | App-Modell bleibt (Mandat, Fassung); Pfad ist über `Diagramm.pfad` auflösbar |
| Ablage-Port | `CodeService` direkt | `DiagrammSpeicher` (Register von `DateiAblage`n) | **`DiagrammAblage`** = Inventar-Methodenschnitt (auflisten, lesen, speichern, löschen, Ordner, verschieben, Änderungsdaten), Standard über `DiagrammSpeicher`/`DateiAblage` |
| Lese-Link | `/drawio.html?diagramm=x&token=y`, Klartext-Token in cie-meta | `/nosec/freigabe/{token}` über `DrawioFreigabeQuelle` | beide: bestehende Freigabe-Links bleiben; Inventar-Form über `LinkTokenStore` |
| Schreib-Link | ja (`/nosec/drawio/diagramm`, Editor ohne Anmeldung) | nein | übernehmen (Inventar gewinnt) |
| Bildlink | `?diagramm=` nur Bild, fensterfüllend, Seitenwahl | `drawio-ansicht.xhtml?id=` | übernehmen |
| Konflikt beim Speichern | SHA-256 `basis` + `ueberschreiben` | `basisFassung`, 409 | App-Fassung bleibt, Hash zusätzlich |
| Reservierter Ordner | `bibliotheken/` | `drawio-bibliotheken` | `bibliotheken/` (Auftrag), alter Name bleibt lesbar |
| Umzug zieht Links nach | ja (`DrawioUmzug`) | Freigabe hängt an der Id, also implizit | über `LinkTokenStore#zieheUm` |
| Warmup/Cache | Spring-Cache + Warmup | keiner | Start-Warmup nur sinnvoll mit langsamer Ablage → Hook |
| Editor | statische Kopie 50 MB (32.0.2), eingecheckt | Maven-Artefakt `drawio-webapp` (31.6.1), entpackt beim Bau | App-Weg bleibt (kein 50-MB-Commit); Version angleichen ist ein eigener Bump |
| nur App | Bildexport ohne Browser, Generator, Wiki-Einbettung, Ablage-Rollen, Mandat | — | bleibt |

### Inventar-Tests (13 Klassen)

`DrawioLinkServiceTest` → Token-Fälle in `JpaLinkTokenStoreTest` (A) und Link-Fälle in C; `DrawioOrdnerTest`,
`DrawioBibliothekTest`, `DrawioFilterTest`, `DrawioVorschauReiterTest`, `DrawioBildControllerTest`,
`DrawioEditorApiControllerTest`, `DrawioLinkAnsichtControllerTest` portierbar; `DrawioCacheTest`,
`DrawioServiceImplTest`, `DrawioWarmupTest` sind Git-/Cache-spezifisch und gehören zum Inventar-Adapter;
`DrawioDateienSperreTest` hängt an `praesentationen/` (D); `DrawioDateiTest` an Upload.

## 4. Präsentation (Inventar `ch.admin.coreit.inventar.praesentation`, 30 Klassen) ↔ `plaintext-z-praesentation` (20 + 10)

Die Modelle sind verschieden: Im Inventar laufen **programmierte Foliensätze live** (Fernbedienung, SSE-Kanal,
Mauszeiger auf der Wand, Detailfolien, Diashows aus Draw.io-Ordnern). Die App **bearbeitet Foliensätze als Dateien**
(Text/Bild/Diagramm-Folien, PDF/PPTX-Export) ohne Live-Kanal. Gemeinsam sind nur Rahmen (16:9), Ablage-Widget und
Lese-Link. Deshalb wird D **additiv**: die Inventar-Mechanik kommt als neuer Teil ins Modul, die App-Foliensätze
werden über eine eigene `FoliensatzQuelle` in Presenter und Fernbedienung sichtbar.

| Klasse | ins Modul | Kopplung |
|---|---|---|
| `Foliensatz`, `FoliensatzQuelle` | ja, SPI | — |
| `PraesentationService`, `PraesentationStand`(+Repo), `PraesentationKanal` | ja | `CoreITEntity` → `SuperModel`; H2 `ddl-auto` → Flyway PostgreSQL |
| `Buehne` (1600x900, Grafik 1504x760) | ja, ohne `bitSeite` | `HtmlSicher` → ins Modul |
| `PraesentationController` (`/nosec/praesentation/…`, SSE `/ereignisse`, `zeiger`, `maus`, `schalten`) | ja | — |
| `DrawioFoliensaetze`, `DrawioDiashowStore`, `DiashowEigenschaften` | ja | `Diagramme`/`Diashows` → API des Draw.io-Moduls (C) |
| `DiashowBackingBean`, `DiashowMenu`, `diashows.xhtml` | ja | Menü unter Admin → konfigurierbar |
| `DiashowLinkService`, `DiashowLinkController`, `DiashowTeilenBean` | ja | `MetaListStore`/`TokenAufrufe` → `LinkTokenStore` (art `diashow`) |
| `PraesentationAblage`, `PraesentationAblageController` | teilweise | Ordner in `MetaListStore` → App-Ablage |
| `PraesentationPptx`, `PraesentationPptxController` | ja | BIT-Vorlage → Property `plaintext.praesentation.pptx-vorlage` (Standard neutral) |
| `DiashowMcpController` | ja | — |
| `PraesentationSvg` | ja (generisch) | — |
| `DefectDojoFoliensaetze`, `CoreItVulnerabilitiesFoliensatz`, `PatchlevelFoliensatz`, `PipelinePatchlevelFoliensatz`, `TestautomationFoliensatz`, `PraesentationGrafiken`, `PraesentationDarstellung` | **nein**, bleiben im Inventar | `spiegel.*`, `ConfluenceService`, `Zahlenreihen`, `Patchlevel` |

Inventar-Tests: `PraesentationNavigationTest`, `PraesentationKanalTest`, `DrawioDiashowStoreTest`,
`DiashowEigenschaftenTest`, `DrawioFoliensaetzeTest`, `PraesentationSvgBeschriftungTest`, `PraesentationPptxTest`,
`DiashowLinkServiceTest`, `DiashowMcpControllerTest` und die nicht-fachlichen Teile von `PraesentationTest`
werden portiert; `CoreItVulnerabilitiesFoliensatzTest` und die Zahlen-/Patchlevel-Fälle bleiben im Inventar.

PPTX: Apache POI `poi-ooxml` 5.5.1 ist in beiden schon da — keine neue Drittabhängigkeit.

## 5. Ports (Vertrag für den Inventar-Adapter)

| Port | Modul | Standard ohne Inventar | Inventar-Adapter |
|---|---|---|---|
| `ch.plaintext.linktoken.LinkTokenStore` | root-interfaces | `JpaLinkTokenStore` (root-freigabe, Tabelle `freigabe_link`) | Konfiglisten `DrawioLinkTokens`, `DiashowLinkTokens`, `FilesTokens` + `TokenAufrufe` |
| `ch.plaintext.files.DateiSpeicher` | root-files | Dateisystem unter `plaintext.files.verzeichnis` | `FileStorageService` (Code-Service-PV), `raeumtSelbstAuf() = true` |
| `DiagrammAblage` | app drawio | über `DiagrammSpeicher`/`DateiAblage` | `CodeService` auf `cie-meta/drawio/` |
| `FoliensatzQuelle` | app praesentation | Draw.io-Ordner `praesentationen/` und App-Foliensätze | Inventar-Fachfoliensätze |
| PPTX-Vorlage | app praesentation | neutrale Vorlage im JAR | BIT-Vorlage per Property |
| Properties | alle | `plaintext.files.*`, `plaintext.drawio.*`, `plaintext.praesentation.*` | Inventar setzt Basis-URL, Menüplatz, Identitätsfrage |

## 6. Offene Entscheide (nicht in dieser Karte zu lösen)

- **Seiten-URLs**: Inventar `drawio.html`, `diashows.html`, `files.html`; App `drawio.html`, `praesentation.html`.
  Die Inventar-Adressen `drawio.html?diagramm=&token=`, `/nosec/praesentation/link/{token}` und
  `/nosec/files/{token}` werden in C/D/B weiter bedient, damit bestehende Token-Links und Confluence-Einbettungen
  nach dem Wechsel funktionieren. In der App leitet `/nosec/praesentation/link/{token}` heute auf
  `/nosec/freigabe/` um — das kollidiert mit dem Inventar-Pfad und braucht einen Entscheid (Vorschlag in D:
  zuerst LinkTokenStore fragen, sonst wie bisher weiterleiten).
- **Klartext- vs. Hash-Tokens**: Das Inventar kann Links in der Übersicht erneut anzeigen (Token im Klartext),
  root nur beim Anlegen. Der Port erlaubt beides (`LinkToken#token()` darf `null` sein); die Oberflächen zeigen
  dann «nur beim Anlegen sichtbar».
- **MCP-Werkzeuge**: Empfehlung wie im Auftrag — sie gehören zum Modul, die Registrierung beim Inventar-MCP macht
  der Adapter.
- **Editor-Kopie**: Inventar checkt 50 MB ein, die App zieht ein Maven-Artefakt. Für die BIT-Pipeline (nur Nexus)
  muss das Artefakt `ch.plaintext.thirdparty:drawio-webapp` mit in `lib/` — Daniel.
- **Versionsschema**: Der Auftrag verlangt SemVer/Conventional Commits; plaintext-root zählt Releases im Minor und
  schreibt deutsche Commit-Betreffe ohne Tags (`CLAUDE.md`). Die PRs folgen dem Repo; neue Module stehen im
  CHANGELOG unter «Added».
