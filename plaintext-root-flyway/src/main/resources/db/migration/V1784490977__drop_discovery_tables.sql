-- Discovery-Feature (plaintext-root-discovery, Cross-App-SSO/App-Switcher per MQTT) vollstaendig
-- entfernt (Daniel-Entscheid 19.07.2026: sauberer Rueckbau). Modul-Dependency ist raus; hier die
-- zugehoerigen Tabellen droppen. Erst discovery_user_session (FK auf discovery_app), dann
-- discovery_app selbst.

DROP TABLE IF EXISTS discovery_user_session;
DROP TABLE IF EXISTS discovery_app;
