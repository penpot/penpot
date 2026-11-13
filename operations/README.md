# Operations

Procedures that are executed by hand on an instance, not by the application.

They are not part of the product: they describe how to do a one-off operation
on a running database, with the exact statements to run and what to check
between them.

Each procedure lives here while the operation is alive. When the operation is
done, the file is either deleted or marked as historical, so the folder does
not grow with procedures that nobody will run again.

| Procedure | Version |
|---|---|
| [Migrate legacy file payloads to `file_data`](2.19.0-migrate-legacy-file-data.md) | 2.19.0 |
| [Partition `file_change` by `file_id`](2.20.0-file-change-partition.md) | 2.20.0 |
