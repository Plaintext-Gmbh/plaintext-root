-- H3-Haertung /nosec/api/claude: SHA-256-Hash des API-Tokens (hex, 64 Zeichen).
-- Uebergangsphase: api_token (Klartext) bleibt bestehen; der Hash wird beim Speichern
-- (Entity-Lifecycle-Hook) bzw. beim ersten erfolgreichen Klartext-Match (Lazy-Migration)
-- befuellt. Der Token-Vergleich laeuft ab jetzt konstantzeitig ueber den Hash.
ALTER TABLE anforderung_api_settings ADD COLUMN api_token_hash VARCHAR(64);
