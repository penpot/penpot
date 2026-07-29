-- Add locked_by column to file_change table for version locking feature
-- This allows users to lock their own saved versions to prevent deletion by others

ALTER TABLE file_change
  ADD COLUMN locked_by uuid NULL REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;

-- Create index for locked versions queries
CREATE INDEX file_change__locked_by__idx ON file_change (locked_by) WHERE locked_by IS NOT NULL;

-- Add comment for documentation
COMMENT ON COLUMN file_change.locked_by IS 'Profile ID of user who has locked this version. Only the creator can lock/unlock their own versions. Locked versions cannot be deleted by others.';