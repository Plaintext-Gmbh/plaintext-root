-- Reparatur #002 (Folge des int-blue-Incidents vom 22.07.2026): cron_config hatte den PK
-- immer schon in der urspruenglichen Migration (V820503545, "CREATE TABLE IF NOT EXISTS ...
-- PRIMARY KEY (ID)") -- aber genau dieses "IF NOT EXISTS" ist die Falle: existierte die
-- Tabelle in einer Umgebung schon vorher (INT, vor dem manuellen Incident-Fix), wurde der PK
-- dort nie angelegt, die Sequence lief unbemerkt aus dem Ruder und der naechste Boot crashte
-- mit Hibernate "Duplicate row". PROD und INT haben den PK inzwischen (PROD von Anfang an,
-- INT durch den manuellen Incident-Fix) -- dieser Guard macht den Zustand fuer jede weitere
-- Umgebung (Wiederherstellung, neue Stage) explizit und automatisch, statt sich auf eine
-- erneute manuelle Reparatur zu verlassen.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'cron_config'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE cron_config ADD PRIMARY KEY (id);
    END IF;
END $$;
