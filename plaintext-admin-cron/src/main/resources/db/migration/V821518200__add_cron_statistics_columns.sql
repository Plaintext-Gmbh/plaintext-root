-- Add columns for persistent cron execution statistics
-- These columns store counter, lastRun, and lastSeconds to survive application restarts

ALTER TABLE cron_config ADD COLUMN counter INTEGER DEFAULT 0;
ALTER TABLE cron_config ADD COLUMN last_run TIMESTAMP;
ALTER TABLE cron_config ADD COLUMN last_seconds INTEGER DEFAULT 0;
