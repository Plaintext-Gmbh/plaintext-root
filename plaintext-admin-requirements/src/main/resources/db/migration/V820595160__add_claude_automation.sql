-- Add Claude automation fields to anforderung table
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS claude_automation_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS cron_expression VARCHAR(100);
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS prompt_template VARCHAR(5000);
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS api_token VARCHAR(255);
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS last_execution_date TIMESTAMP;
ALTER TABLE anforderung ADD COLUMN IF NOT EXISTS next_execution_date TIMESTAMP;

-- Create claude_prompt table
CREATE TABLE IF NOT EXISTS claude_prompt (
    id BIGSERIAL PRIMARY KEY,
    prompt_number VARCHAR(10) NOT NULL UNIQUE,
    anforderung_id BIGINT NOT NULL,
    mandat VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    prompt_text VARCHAR(10000),
    created_date TIMESTAMP NOT NULL,
    sent_date TIMESTAMP,
    acknowledged_date TIMESTAMP,
    timeout_date TIMESTAMP,
    retry_count INTEGER DEFAULT 0,
    error_message VARCHAR(1000),
    lockfile_path VARCHAR(500)
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_claude_prompt_number ON claude_prompt(prompt_number);
CREATE INDEX IF NOT EXISTS idx_claude_prompt_status ON claude_prompt(status);
CREATE INDEX IF NOT EXISTS idx_claude_prompt_anforderung ON claude_prompt(anforderung_id);
