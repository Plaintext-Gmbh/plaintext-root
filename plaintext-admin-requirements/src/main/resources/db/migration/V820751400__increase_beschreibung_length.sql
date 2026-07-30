-- Increase beschreibung field length from 5000 to 10000 characters
-- This fixes the issue where anforderungen with 6174+ characters could not be saved
-- HSQLDB Syntax: ALTER COLUMN ... TYPE

ALTER TABLE anforderung ALTER COLUMN beschreibung TYPE VARCHAR(10000);
