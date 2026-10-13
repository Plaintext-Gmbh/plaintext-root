-- Karte 1449 S-12 (Entscheid Daniel, 13.10.2026): der Klartext des Claude-Automation-Tokens
-- verlaesst die Datenbank. Geprueft wird seit V1783625400 nur noch ueber api_token_hash.
-- Schritt 1: Zeilen ohne Hash bekommen ihn aus dem Klartext (in app-prod hatten am 13.10.2026
-- beide Zeilen Klartext und keinen Hash). Gleiche Rechnung wie ApiTokenHasher.sha256Hex:
-- SHA-256 ueber UTF-8, Hex in Kleinbuchstaben. sha256() ist ab PostgreSQL 11 eingebaut.
UPDATE anforderung_api_settings
   SET api_token_hash = encode(sha256(convert_to(api_token, 'UTF8')), 'hex')
 WHERE coalesce(api_token, '') <> ''
   AND coalesce(api_token_hash, '') = '';

-- Schritt 2: Klartext leeren, wo ein Hash steht; der Token gilt ueber den Hash weiter.
UPDATE anforderung_api_settings
   SET api_token = NULL
 WHERE api_token IS NOT NULL
   AND coalesce(api_token_hash, '') <> '';

UPDATE anforderung_api_settings
   SET api_token = NULL
 WHERE api_token = '';
