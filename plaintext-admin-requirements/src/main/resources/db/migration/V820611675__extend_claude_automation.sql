-- Extend Claude Automation System
-- Add new fields to anforderung table and create constraint_template table

-- Create constraint_template table first (for foreign key)
CREATE TABLE constraint_template (
    id BIGSERIAL PRIMARY KEY,
    titel VARCHAR(200) NOT NULL UNIQUE,
    beschreibung VARCHAR(1000),
    constraints_content TEXT NOT NULL,
    created_date TIMESTAMP,
    created_by VARCHAR(255),
    last_modified_date TIMESTAMP,
    last_modified_by VARCHAR(255)
);

-- Add new fields to anforderung table
ALTER TABLE anforderung ADD COLUMN claude_summary TEXT;
ALTER TABLE anforderung ADD COLUMN user_answer TEXT;
ALTER TABLE anforderung ADD COLUMN requires_user_answer BOOLEAN DEFAULT FALSE;
ALTER TABLE anforderung ADD COLUMN target_modules VARCHAR(500);
ALTER TABLE anforderung ADD COLUMN branch_naming_pattern VARCHAR(200);
ALTER TABLE anforderung ADD COLUMN development_cycle_info VARCHAR(2000);
ALTER TABLE anforderung ADD COLUMN is_default BOOLEAN DEFAULT FALSE;
ALTER TABLE anforderung ADD COLUMN constraint_template_id BIGINT;

-- Add indexes for better query performance
CREATE INDEX idx_anforderung_is_default ON anforderung(is_default);
CREATE INDEX idx_anforderung_next_execution ON anforderung(next_execution_date);
CREATE INDEX idx_anforderung_constraint_template ON anforderung(constraint_template_id);
CREATE INDEX idx_anforderung_requires_user_answer ON anforderung(requires_user_answer);
CREATE INDEX idx_constraint_template_titel ON constraint_template(titel);

-- Add foreign key constraint
ALTER TABLE anforderung ADD CONSTRAINT fk_anforderung_constraint_template
    FOREIGN KEY (constraint_template_id) REFERENCES constraint_template(id);
