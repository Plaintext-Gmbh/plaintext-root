# Plaintext-Sidecar-Protokoll, Version 1

Ein **Sidecar** ist ein eigenständiger Container, der einer Plaintext-Anwendung eine Dienstleistung
anbietet: WhatsApp- oder Signal-Anbindung, Bilderkennung, Bildumrechnung und Ähnliches. Damit eine
Anwendung einen solchen Dienst findet, seinen Zustand anzeigt und ihn ohne eigenen Code nutzt,
beantwortet jeder Sidecar zwei kleine HTTP-Anfragen. Mehr verlangt das Protokoll nicht.

Gegenstück in der Anwendung ist das Root-Modul `plaintext-admin-sidecars`. Es fragt die Dienste ab
und zeigt sie unter *Root → Sidecars*. Dort lässt sich auch ein Token hinterlegen. Über MCP gibt das
Modul alles an ein LLM weiter, und andere Module finden über `SidecarRegister` einen Dienst nach
seiner **Fähigkeit** statt nach seinem Containernamen.

## 1. Beschreibung: `GET /.well-known/plaintext-sidecar`

Der Aufruf braucht **keinen Token** und antwortet in höchstens 3 Sekunden mit `200` und
`Content-Type: application/json`:

```json
{
  "protokoll": "plaintext-sidecar/1",
  "name": "fotos",
  "titel": "Fotos: Erkennung und Bildumrechnung",
  "beschreibung": "Bildvektoren, Stichworte und Gesichter (Immich-Modelle) sowie HEIC→JPEG ohne Metadaten.",
  "version": "1.4.0",
  "status": "ok",
  "statusText": "Modelle geladen",
  "auth": { "art": "bearer" },
  "teile": [
    { "name": "modell.clip", "status": "ok", "text": "ViT-B-32__openai" },
    { "name": "modell.gesichter", "status": "ok", "text": "buffalo_l" }
  ],
  "faehigkeiten": [
    {
      "id": "bild.vorschau",
      "titel": "Vorschaubild erzeugen",
      "beschreibung": "Rechnet ein Bild (JPEG, HEIC, PNG …) in ein JPEG der gewünschten Breite um, mit EXIF-Drehung und ohne Metadaten.",
      "methode": "POST",
      "pfad": "/bild/vorschau",
      "eingabe": { "inhalt": "image/*", "parameter": { "type": "object", "properties": { "breite": { "type": "integer", "minimum": 16, "maximum": 4096 } } } },
      "ausgabe": { "inhalt": "image/jpeg" },
      "auth": true,
      "mcp": false,
      "seiteneffekt": "keiner"
    }
  ],
  "doku": "https://github.com/Plaintext-Gmbh/plaintext-sidecar-fotos#readme"
}
```

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `protokoll` | ja | Immer `plaintext-sidecar/1`. Eine neue Hauptversion bekommt eine neue Zahl. Ein Aufrufer, der die Zahl nicht kennt, zeigt den Dienst als «Protokoll unbekannt» an und nutzt ihn nicht. |
| `name` | ja | Kurz, stabil, `[a-z0-9-]{1,40}`, z. B. `whatsapp`, `signal`, `fotos`. Er ist der Schlüssel in der Registry, der Token hängt an ihm. |
| `titel`, `beschreibung` | ja / nein | Für Menschen und für ein LLM, das plant. |
| `version` | ja | Version des Dienstes (nicht des Protokolls). |
| `status` | ja | `ok`, `eingeschraenkt` (läuft, aber ein Teil fehlt, z. B. WhatsApp nicht gekoppelt) oder `fehler`. |
| `statusText` | nein | Ein Satz zum Status. |
| `auth.art` | ja | `bearer` (alle Fähigkeiten mit `auth: true` brauchen `Authorization: Bearer <token>`) oder `keine`. |
| `teile` | nein | Unterzustände mit `name`, `status` und `text`. |
| `faehigkeiten` | ja | Liste, darf leer sein, siehe Abschnitt 3. |
| `doku` | nein | URL der ausführlichen Beschreibung. |

**Was nie in die Beschreibung gehört:** Tokens, Passwörter, Telefonnummern, Namen, Nachrichteninhalte
oder interne Pfade. Die Antwort ist ohne Anmeldung lesbar.

## 2. Token prüfen: `GET /.well-known/plaintext-sidecar/auth`

Der Aufruf kommt mit `Authorization: Bearer <token>` und hat genau zwei Antworten:

- `200` mit `{"gueltig": true}` bei einem gültigen Token,
- `401` bei einem fehlenden oder falschen Token. Die Antwort darf nicht verraten, ob es einen Token gibt.

Bei `auth.art = keine` antwortet der Aufruf immer mit `200`. Den Vergleich macht der Dienst in
konstanter Zeit (z. B. `crypto.timingSafeEqual`, `MessageDigest.isEqual`).

## 3. Fähigkeiten

Eine Fähigkeit ist ein Endpunkt des Dienstes mit einer **stabilen, gepunkteten `id`**. Andere Module
und ein LLM planen mit dieser `id`, nicht mit dem Containernamen. Bieten zwei Dienste dieselbe `id`
an, sind sie austauschbar.

| Feld | Pflicht | Bedeutung |
|---|---|---|
| `id` | ja | `bereich.tätigkeit[.genauer]`, Kleinbuchstaben, z. B. `bild.vorschau`, `gesicht.erkennen`, `nachricht.senden.whatsapp`, `kontakt.erreichbar.signal`. |
| `titel`, `beschreibung` | ja | Was die Fähigkeit tut, wofür man sie braucht, was sie nicht tut. |
| `methode`, `pfad` | ja | HTTP-Methode und Pfad relativ zur Basis-URL, ohne Platzhalter im Pfad. Parameter gehören in Query oder Body. |
| `eingabe` | nein | JSON-Schema des Bodys bzw. der Query (`parameter`). Bei binären Daten zusätzlich `inhalt` mit dem Medientyp. |
| `ausgabe` | nein | JSON-Schema der Antwort oder `inhalt` bei binärer Antwort. |
| `auth` | ja | `true`, wenn der Token nötig ist. |
| `mcp` | nein, Vorgabe `false` | `true` erlaubt den Aufruf über das allgemeine MCP-Werkzeug `rufe_sidecar_faehigkeit`. Nur setzen, wenn ein LLM die Fähigkeit ohne Rückfrage auslösen darf. Fähigkeiten mit `seiteneffekt: aussen` ruft die Registry über dieses Werkzeug **nie** auf, auch nicht mit `mcp: true`. |
| `seiteneffekt` | ja | `keiner` (nur lesen oder rechnen), `intern` (ändert Zustand im Dienst) oder `aussen` (wirkt nach aussen, z. B. sendet eine Nachricht). |

## 4. Wie eine Anwendung die Sidecars nutzt

- **Übersicht:** *Root → Sidecars* zeigt jeden Dienst mit Status, Teilen, Fähigkeiten und Zugang
  (kein Token, Token gültig, Token ungültig). Dort wird der Token hinterlegt, er wird sofort gegen
  `…/auth` geprüft.
- **Module:** `SidecarRegister.fuer("bild.vorschau")` liefert Basis-URL und Token eines erreichbaren
  Dienstes mit dieser Fähigkeit (Status `ok` vor `eingeschraenkt`, nie `fehler`).
- **MCP:** `list_sidecars`, `get_sidecar` (vollständige Beschreibung als Planungsgrundlage),
  `find_sidecar_faehigkeit`, `pruefe_sidecar`, `registriere_sidecar`, `set_sidecar_token` (nur
  schreiben) und `rufe_sidecar_faehigkeit` (nur `mcp: true` und kein `aussen`). Alles nur mit der
  Rolle ROOT, Ändern und Aufrufen zusätzlich mit `scope=ADMIN`.

## 5. Betrieb

- Der Dienst lauscht nur im internen Docker-Netz der Anwendung. Einen veröffentlichten Port braucht er
  nicht, und Container dürfen keinen Docker-Socket bekommen, um sich gegenseitig zu entdecken.
- Den Token setzt der Betreiber im Dienst (z. B. Umgebungsvariable `SIDECAR_TOKEN`) und hinterlegt
  denselben Wert unter *Root → Sidecars*. Die Anwendung schickt ihn bei jedem Aufruf einer Fähigkeit
  mit `auth: true`.
- Welche Dienste eine Anwendung kennt, steht in `plaintext.sidecars` (`name=url`, kommagetrennt) oder
  wird unter *Root → Sidecars* von Hand ergänzt. Von Hand gehen nur Hosts aus
  `plaintext.sidecars.erlaubte-hosts` oder aus der Konfiguration, damit die Übersicht kein Werkzeug
  wird, um beliebige Adressen im LAN abzufragen.
- Repos heissen `plaintext-sidecar-<name>`, sind privat und bauen mit Woodpecker in die Registry auf
  dem NAS.

## 6. Prüfliste für einen neuen Sidecar

1. `GET /.well-known/plaintext-sidecar` liefert ohne Token `200` mit allen Pflichtfeldern, ohne
   Geheimnisse und ohne Personendaten.
2. `GET /.well-known/plaintext-sidecar/auth` liefert mit richtigem Token `200`, mit falschem und
   ohne Token `401`.
3. Jede Fähigkeit mit `auth: true` lehnt einen Aufruf ohne Token mit `401` ab.
4. `status` sagt die Wahrheit: Was nicht geht, meldet `eingeschraenkt` oder `fehler` mit
   `statusText` und nicht `ok`.
5. Der Dienst erscheint nach dem Eintrag in `plaintext.sidecars` unter *Root → Sidecars* mit
   Status, Version, Fähigkeiten und nach dem Hinterlegen des Tokens mit «Token gültig».

Mit dem Beispiel-Repo `plaintext-sidecar-beispiel` lässt sich das in wenigen Minuten nachbauen.
