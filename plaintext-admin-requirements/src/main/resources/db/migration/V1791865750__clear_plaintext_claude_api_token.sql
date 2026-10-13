-- Karte 1449 S-12 (Entscheid Daniel, 13.10.2026): der Klartext des Claude-Automation-Tokens
-- verlaesst die Datenbank. Geprueft wird seit V1783625400 nur noch ueber api_token_hash.
-- Der Hash bleibt unberuehrt. Zeilen OHNE Hash behalten ihren Klartext, sonst waere ihr Token
-- lautlos ungueltig. Die Lazy-Migration ergaenzt ihren Hash beim naechsten Gebrauch; den Klartext
-- leert erst ein neu erzeugter Token in der Einstellungsseite.
UPDATE anforderung_api_settings
   SET api_token = NULL
 WHERE api_token IS NOT NULL
   AND api_token_hash IS NOT NULL
   AND api_token_hash <> '';

UPDATE anforderung_api_settings
   SET api_token = NULL
 WHERE api_token = '';
