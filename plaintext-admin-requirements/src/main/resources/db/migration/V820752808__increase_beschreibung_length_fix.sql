-- Fix: Increase beschreibung field length from 5000 to 10000 characters
-- Previous patch V820751400 failed due to incorrect MySQL syntax
-- This patch uses correct HSQLDB syntax

ALTER TABLE anforderung ALTER COLUMN beschreibung TYPE VARCHAR(10000);
