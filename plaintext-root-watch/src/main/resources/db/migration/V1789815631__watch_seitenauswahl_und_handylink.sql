-- Karten 1257/1260: Seitenauswahl je Benutzer und der persoenliche Handy-Link.
--
-- WARUM EINE SPALTE UND KEINE EIGENE TABELLE. `available()` wird bei jedem Seitenwechsel und
-- bei jedem Rendern fuer JEDE registrierte Seite ausgewertet. Mit einer Spalte kostet das
-- nichts: die eine Zeile des Benutzers liest der Zustandsdienst ohnehin schon. Eine
-- Zuordnungstabelle haette pro Anzeige eine zweite Abfrage oder n Einzelabfragen bedeutet,
-- und das auf einem Geraet, dessen Reiz die kurze Ladezeit ist.
--
-- WARUM DIE ABGESCHALTETEN UND NICHT DIE EINGESCHALTETEN GESPEICHERT WERDEN. Eine neue
-- Modulseite ist damit von sich aus sichtbar — genau das heutige Verhalten. Stuende die
-- Positivliste da, muesste jeder Benutzer jede kuenftige Seite erst anhaken, und ein Release
-- wuerde Seiten stillschweigend verschwinden lassen. Ein Modul, das verschwindet, laesst hier
-- nur eine harmlose Kennung zurueck, keinen Fremdschluessel.
--
-- WAS BEWUSST NICHT HIER STEHT: der ausgestellte JWT des Handy-Links. Gespeichert werden nur
-- Zustand, Zeitpunkt und die `jti` des Tokens. Der Token selbst ist ein Dauerausweis; er liegt
-- wie jeder andere Token ausschliesslich als SHA-256 in `api_token`. Diese Tabelle wird zudem
-- ueber den WatchModuleDescriptor exportiert — ein Klartext-Token waere damit in jeder
-- Moduldatei.
--
-- PostgreSQL.
ALTER TABLE watch_user_state ADD COLUMN IF NOT EXISTS abgeschaltete_seiten VARCHAR(1024);
ALTER TABLE watch_user_state ADD COLUMN IF NOT EXISTS handy_link_aktiv BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE watch_user_state ADD COLUMN IF NOT EXISTS handy_link_erstellt TIMESTAMP;
ALTER TABLE watch_user_state ADD COLUMN IF NOT EXISTS handy_link_jti VARCHAR(64);

-- `testseite_aktiv` wird NICHT in die neue Auswahl uebernommen und bleibt unangetastet stehen.
--
-- Das ist Absicht und kein Vergessen: mit Karte 1260 wird aus der Elementseite die Uebersicht
-- mit den Schaltern. Sie ist der einzige Weg zu den Schaltern und muss deshalb bei jedem
-- sichtbar sein — haette man den alten Wert uebernommen, waere sie fuer alle aus, die den
-- Schalter nie umgelegt haben, und niemand kaeme mehr an die Einstellung.
--
-- Aus dem Weg ist die Seite trotzdem: sie laeuft ab 1260 nicht mehr im Umlauf mit
-- (`WatchPage.imUmlauf()`), zaehlt also nicht in „x/n" und wird beim Blaettern uebersprungen.
-- Die Spalte bleibt als Beleg des alten Zustands erhalten; gelesen wird sie nicht mehr.
