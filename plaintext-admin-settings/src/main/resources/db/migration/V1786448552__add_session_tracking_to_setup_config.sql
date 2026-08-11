-- Karte 627: Schalter fuer die Aufzeichnung von Sitzungsinformationen (USER_SESSION) je Mandant.
-- Default TRUE: Bestandsinstallationen zeichnen heute auf, und dieses Verhalten darf sich durch
-- das Einspielen des Patches nicht still aendern.
ALTER TABLE SETUP_CONFIG ADD COLUMN IF NOT EXISTS SESSION_TRACKING_ENABLED BOOLEAN DEFAULT TRUE NOT NULL;
