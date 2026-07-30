-- Refactor Anforderung table: remove zugewiesen_an and faelligkeit_datum, add wiederkehrend
-- This aligns with business requirement to simplify the model

-- Add new field: wiederkehrend (recurring)
ALTER TABLE anforderung ADD COLUMN wiederkehrend BOOLEAN DEFAULT FALSE;

-- Remove old fields that are no longer needed
ALTER TABLE anforderung DROP COLUMN zugewiesen_an;
ALTER TABLE anforderung DROP COLUMN faelligkeit_datum;

-- Note: last_modified_date already exists (from Spring Data JPA auditing) and serves as "letzte Bearbeitung"
