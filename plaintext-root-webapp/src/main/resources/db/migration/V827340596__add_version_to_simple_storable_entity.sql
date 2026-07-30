-- Add optimistic locking version column to simple_storable_entity
ALTER TABLE simple_storable_entity ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
