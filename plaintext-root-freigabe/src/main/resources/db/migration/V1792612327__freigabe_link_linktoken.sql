-- Karte 1559: dieselbe Tabelle traegt auch die Token-Links des LinkTokenStore (Draw.io, Diashows, Files).
-- Deren Objekt ist ein Textschluessel (Dateipfad, Titel), keine Id: objekt_id wird optional, schluessel neu.
-- Freigabe-Links (Karte 1476) haben weiter objekt_id und keinen schluessel. PostgreSQL.
ALTER TABLE freigabe_link ALTER COLUMN objekt_id DROP NOT NULL;
ALTER TABLE freigabe_link ADD COLUMN IF NOT EXISTS schluessel VARCHAR(1000);
ALTER TABLE freigabe_link ADD COLUMN IF NOT EXISTS besitzer VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_freigabe_link_schluessel ON freigabe_link (typ, schluessel);
