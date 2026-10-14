-- Karte 1449 S-12, Nachtrag (14.10.2026): In app-prod lief V1791865750 in der ERSTEN Fassung
-- (Checksumme 2042371949, PR-Zwischenstand c3670111), die nur dort leert, wo schon ein Hash steht.
-- Die korrigierte Fassung mit Hash-Nachtrag laeuft dort nie mehr (Version schon eingetragen).
-- Diese Migration wiederholt beide Schritte; idempotent, auf Installationen mit der richtigen
-- Fassung aendert sie nichts. Rechnung wie ApiTokenHasher.sha256Hex: SHA-256 ueber UTF-8, Hex klein.
UPDATE anforderung_api_settings
   SET api_token_hash = encode(sha256(convert_to(api_token, 'UTF8')), 'hex')
 WHERE coalesce(api_token, '') <> ''
   AND coalesce(api_token_hash, '') = '';

UPDATE anforderung_api_settings
   SET api_token = NULL
 WHERE api_token IS NOT NULL
   AND coalesce(api_token_hash, '') <> '';
