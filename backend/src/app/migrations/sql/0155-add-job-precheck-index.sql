-- Add partial index for cron precheck query
-- The cron scheduler checks for active jobs with the same name+label to prevent
-- overlapping executions. This index speeds up that query as the job table grows.

CREATE INDEX IF NOT EXISTS job_name_label_active_idx
    ON job (name, label)
    WHERE status IN ('new', 'scheduled', 'running', 'retry');
