-- Karte 664: Widerruf eines MCP-Tokens war bei app/guild/schuetu wirkungslos (validation=JWT).
-- Der Filter kann eine jti-Blocklist auswerten, aber die Zeile kannte ihren eigenen jti nicht.
--
-- NULLABLE mit Absicht: Bestandszeilen koennen ihren jti nicht nachtraeglich erfahren (er steht
-- nur im ausgestellten Token). Ein NULL bedeutet "unbekannt" und damit "nicht widerrufen" — das
-- haelt JWT-only-Tokens (Zeiterfassung-Uhr, Juriwagen, minten) unberuehrt, die gar keine Zeile
-- in dieser Tabelle haben.
ALTER TABLE API_TOKEN ADD COLUMN IF NOT EXISTS JTI VARCHAR(64);

-- Der Lookup laeuft im Auth-Pfad jedes MCP-Requests -> ohne Index waere es ein Table-Scan.
CREATE INDEX IF NOT EXISTS IDX_API_TOKEN_JTI ON API_TOKEN (JTI);
