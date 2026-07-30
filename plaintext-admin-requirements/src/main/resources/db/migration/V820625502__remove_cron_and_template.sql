-- Remove cron_expression and prompt_template columns from anforderung table
-- These fields are no longer needed as Claude automation now uses REST API instead of cron jobs

ALTER TABLE anforderung DROP COLUMN cron_expression;
ALTER TABLE anforderung DROP COLUMN prompt_template;
