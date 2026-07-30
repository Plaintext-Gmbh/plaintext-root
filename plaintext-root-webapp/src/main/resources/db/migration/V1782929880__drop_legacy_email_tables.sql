-- PR D: Alt-Mail-Tabellen des entfernten Moduls plaintext-root-email endgültig entfernen.
-- email_config/email_config_v2: Konfigurationen wurden zuvor nach mail_account migriert (App: V1782913495).
-- email/email_attachment: die alten gesendeten/empfangenen Mails werden bewusst verworfen (bestätigt).
-- Liegt im root-webapp und läuft daher sowohl auf der root-eigenen DB als auch – da plaintext-app-webapp
-- root-webapp bündelt – auf der App-DB. HSQLDB-/PostgreSQL-kompatibel; keine FKs -> kein CASCADE nötig.
DROP TABLE IF EXISTS email_attachment;
DROP TABLE IF EXISTS email;
DROP TABLE IF EXISTS email_config;
DROP TABLE IF EXISTS email_config_v2;
