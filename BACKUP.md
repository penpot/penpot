# Backup + recovery — penpot (Cocoon AI base fork)

Required per cai-portal ADR 0004 D7. CI gates a live deploy on this
file being present + non-empty.

This is the **base-fork BACKUP.md**. Tenant forks (cai-penpot etc.)
inherit and can extend with tenant-specific overrides.

## What gets backed up

- **PostgreSQL** (PG-on-EC2 per ADR D7): EBS snapshots via DLM, daily,
  7-day retention. Snapshots tagged `cai-foreign:penpot` (tenant forks
  override the tag with their service name, e.g. `cai-foreign:cai-penpot`).
- **RPO**: ~24h (daily snapshot cadence). Volume-level / crash-consistent;
  PG starts from snapshot via WAL replay.
- **Redis** (ElastiCache Serverless): **not backed up** (ephemeral).
  Penpot tolerates cold-cache start.
- **S3 bucket** (`cai-foreign-<service>-<env>`): versioning on, 30-day
  noncurrent-version lifecycle. Penpot project files (binary blobs)
  live here; metadata is in PG.

## Recovery procedures

### PG-on-EC2 restore from snapshot

1. `aws ec2 describe-snapshots --filters Name=tag:cai-foreign,Values=<service>`
   — find the snapshot at the target timestamp.
2. `aws ec2 create-volume --snapshot-id <snap> --availability-zone <az> --volume-type gp3`.
3. SSM into the PG instance, stop PG, detach the live volume, attach
   the restored one, restart.
4. Verify via `psql ... \\dt` and a known query.

### S3 object restore

1. `aws s3api list-object-versions --bucket cai-foreign-<service>-<env>`.
2. `aws s3api copy-object` from the prior version ID.

## Drill cadence

Monthly. Documented in cai-portal/docs/runbooks/foreign-app-deploy.md.
