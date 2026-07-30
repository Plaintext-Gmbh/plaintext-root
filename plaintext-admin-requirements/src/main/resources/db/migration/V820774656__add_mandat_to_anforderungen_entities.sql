-- Add mandat field to anforderung_api_settings, howto, and constraint_template
-- This enables multi-tenant support for the Anforderungen module

-- 1. Add mandat column to anforderung_api_settings
ALTER TABLE anforderung_api_settings ADD COLUMN mandat VARCHAR(100);
UPDATE anforderung_api_settings SET mandat = 'MIGRATION' WHERE mandat IS NULL;
ALTER TABLE anforderung_api_settings ALTER COLUMN mandat SET NOT NULL;

-- 2. Add mandat column to howto
ALTER TABLE howto ADD COLUMN mandat VARCHAR(100);
UPDATE howto SET mandat = 'MIGRATION' WHERE mandat IS NULL;
ALTER TABLE howto ALTER COLUMN mandat SET NOT NULL;

-- 3. Add mandat column to constraint_template
ALTER TABLE constraint_template ADD COLUMN mandat VARCHAR(100);
UPDATE constraint_template SET mandat = 'MIGRATION' WHERE mandat IS NULL;
ALTER TABLE constraint_template ALTER COLUMN mandat SET NOT NULL;

-- 4. Create indexes for mandat columns
CREATE INDEX idx_api_settings_mandat ON anforderung_api_settings(mandat);
CREATE INDEX idx_howto_mandat ON howto(mandat);
CREATE INDEX idx_constraint_template_mandat ON constraint_template(mandat);

-- 5. Drop old unique constraints and create new composite unique constraints
-- anforderung_api_settings: mandat must be unique (one settings per mandat)
CREATE UNIQUE INDEX idx_api_settings_mandat_unique ON anforderung_api_settings(mandat);

-- howto: mandat + name must be unique (same name allowed across different mandats)
DROP INDEX IF EXISTS idx_howto_name;
CREATE INDEX idx_howto_name ON howto(name);
CREATE UNIQUE INDEX idx_howto_mandat_name ON howto(mandat, name);

-- constraint_template: mandat + titel must be unique (same titel allowed across different mandats)
DROP INDEX IF EXISTS idx_constraint_template_titel;
CREATE INDEX idx_constraint_template_titel ON constraint_template(titel);
CREATE UNIQUE INDEX idx_constraint_template_mandat_titel ON constraint_template(mandat, titel);
