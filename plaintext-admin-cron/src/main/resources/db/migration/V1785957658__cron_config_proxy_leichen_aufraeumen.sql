-- Karte 574 — Aufraeumen nach dem Cron-Namenswechsel vom 03.08.2026 (PostgreSQL).
--
-- Was passiert war: Bis zum 03.08.2026 lag ein Job unter seinem CGLIB-Proxy-Namen in
-- cron_config ("KontaktEmailAvisTrigger$$SpringCGLIB$$0"), danach unter dem Klassennamen. Der
-- CronController suchte nur exakt; er fand nichts und legte eine neue Zeile mit den
-- Code-Defaults an. Ergebnis in plaintext-app PROD: 99 verwaiste Zeilen ueber 14 Jobs, und 22
-- bewusst abgeschaltete Boot-Laeufe waren wieder an. Ohne Fehlermeldung — der Start blieb gruen,
-- die Einstellungen galten nur nicht mehr. Aufgefallen ist es an einer Mail-Flut.
--
-- Diese Migration tut fuer JEDE Umgebung das, was in plaintext-app PROD am 05.08.2026 von Hand
-- gemacht wurde (Entscheidung Daniel, Karte 574):
--
--   1. startup = false von der alten auf die neue Zeile uebernehmen — das ist die aktive
--      Fehlfunktion (ein Job, der bei jedem Containerstart laeuft, obwohl er abgeschaltet war).
--   2. Die alten Zeilen entfernen, ABER NUR die, deren Job unter dem neuen Namen wirklich
--      existiert.
--
-- Bewusst NICHT uebernommen: cron_expression und enabled. Sie bleiben auf dem Code-Default.
-- Begruendung Daniels: halbjahresalte Einstellungen nicht unbesehen zurueckholen.
--
-- Bewusst NICHT geloescht: alte Zeilen ohne neue Entsprechung (in app PROD 17 Stueck, v.a.
-- EmailReceiveCron/EmailSendCron). Dort ist unklar, ob der Job wirklich weg ist oder gerade nur
-- nicht registriert war — und eine geloeschte Einstellung kommt nicht zurueck. Sie bleiben
-- sichtbar liegen; darueber entscheidet ein Mensch, nicht diese Migration.
--
-- Die Wiederholung verhindert der CronController selbst (adoptLegacyProxyRow): Er uebernimmt die
-- Bestandszeile unter altem Namen, statt eine neue anzulegen. Diese Migration raeumt nur den
-- bereits entstandenen Bestand auf.
--
-- Merkmal einer Proxy-Zeile ist das "$$" im Namen: Es trennt den Klassennamen von einem
-- generierten Suffix und kann in einem Java-Klassennamen nicht vorkommen.

-- 1) Abgeschaltete Boot-Laeufe zurueckholen
UPDATE cron_config neu
   SET startup            = false,
       last_modified_by   = 'Karte 574 (Migration)',
       last_modified_date = now()
  FROM cron_config alt
 WHERE position('$$' in alt.cron_name) > 0
   AND alt.startup = false
   AND neu.mandat = alt.mandat
   AND neu.cron_name = split_part(alt.cron_name, '$$', 1)
   AND position('$$' in neu.cron_name) = 0
   AND neu.startup = true;

-- 2) Proxy-Leichen entfernen — nur mit vorhandener Entsprechung unter dem neuen Namen
DELETE FROM cron_config alt
 WHERE position('$$' in alt.cron_name) > 0
   AND EXISTS (SELECT 1
                 FROM cron_config neu
                WHERE neu.mandat = alt.mandat
                  AND neu.cron_name = split_part(alt.cron_name, '$$', 1)
                  AND position('$$' in neu.cron_name) = 0);
