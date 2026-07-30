-- Verwaiste Alt-Tabelle TEXT2 (ch.emad-Aera, 2017) entfernen: der zugehoerige generische XML-Blob-Store
-- Text2/TextRepository2/XstreamStore2/Xstream2Storable wurde in plaintext-root PR #222 geloescht
-- (Task 014 Fund 1); kein Entity/Code referenziert die Tabelle mehr.
-- PROD-Count auf plaintext-root-db-prod von Daniel geprueft: 0 Zeilen (24.07.2026) -> folgenlos (Task 014b).
-- Laeuft (wie V1782929880__drop_legacy_email_tables) sowohl auf der root-eigenen DB als auch auf der
-- App-DB, da plaintext-app-webapp root-webapp buendelt. HSQLDB-/PostgreSQL-kompatibel; keine FKs -> kein CASCADE.
DROP TABLE IF EXISTS text2;
