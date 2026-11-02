---
title: 3.06. Backend Guide
desc: "Penpot Technical Guide: Backend basics - REPL setup, loading fixtures, database migrations, and clj-kondo linting to speed development workflows."
---

# Backend guide #

This guide collects some basic information on the backend application.

## REPL ##

_Note:_ When in development mode, the backend spins up a traditional nREPL socket on port 6064.
If you are experimenting locally, you can connect to it using your Clojure editor or
with `backend/scripts/nrepl`, which starts a [REPLy client](https://github.com/trptcolin/reply),
[see here][1] for more information.

[1]: /technical-guide/developer/devenv/#backend

In the devenv environment you can execute `backend/scripts/repl` to open a
Clojure interactive shell ([REPL](https://codewith.mu/en/tutorials/1.0/repl)) (this is not a socket-based
REPL, but a local, in-process console (over stdin/stdout) with some fancy line-editing and colors). Note
that the backend must be stopped before executing this script, otherwise it will fail with `Port already
in use: 9090`.

Once there, you can execute <code class="language-clojure">(restart)</code> to load and execute the backend
process, or to reload it after making changes to the source code.

Then you have access to all backend code. You can import and use any function
o read any global variable:

```clojure
(require '[app.some.namespace :as some])
(some/your-function arg1 arg2)
```

There is a specific namespace <code class="language-clojure">app.srepl</code> with some functions useful to be
executed from the repl and perform some tasks manually. Most of them accept
a <code class="language-clojure">system</code> parameter. There is a global variable with this name, that contains
the runtime information and configuration needed for the functions to run.

For example:

```clojure
(require '[app.srepl.main :as srepl])
(srepl/send-test-email! system "test@example.com")
```


## Fixtures ##

This is a development feature that allows populating the database with a
good amount of content (typically used to test the application or to run
performance tweaks on queries).

In order to load fixtures, enter the REPL environment with the <code class="language-clojure">backend/scripts/repl</code>
script, and then execute <code class="language-clojure">(app.cli.fixtures/run {:preset :small})</code>.

You also can execute this as a standalone script with:

```bash
clojure -Adev -X:fn-fixtures
```

_NOTE:_ This is an optional step because the application can start with an
empty database.

The above will create several users that can be used to login
into the application. All of them follow the pattern:

- Username: <code class="language-text">profileN@example.com</code>
- Password: <code class="language-text">123123</code>

Where <code class="language-text">N</code> is a number from 0 to 5 on the default fixture parameters.


## Migrations ##

The database migrations are located in two directories:

- <code class="language-text">src/app/migrations</code> (contains migration scripts in clojure)
- <code class="language-text">src/app/migrations/sql</code> (contains the pure SQL migrations)

The SQL migration naming consists in the following:

```bash
XXXX-<add|mod|del|drop|[...verb...]>-<table-name>-<any-additional-text>
```

Examples:

```bash
0025-del-generic-tokens-table
0026-mod-profile-table-add-is-active-field
```

**NOTE**: if table name has more than one word, we still use <code class="language-text">-</code> as a separator.

### Large tables

A migration runs inside a single transaction, so it cannot use `CONCURRENTLY`,
and a `CREATE INDEX` in a migration locks the table against writes until the
whole run commits.

Write the migration as plain, idempotent SQL: `ADD COLUMN IF NOT EXISTS`,
`CREATE INDEX IF NOT EXISTS`, and `DROP CONSTRAINT IF EXISTS` followed by
`ADD CONSTRAINT`. Do not encode shape checks as `pg_catalog` introspection.
They are easy to get wrong (forgetting `NOT condeferred` accepts an
`INITIALLY DEFERRED` foreign key, which moves the integrity check to commit) and
they duplicate what the runbook can verify by hand.

On a large table, build the column and the index out of band before deploying,
with `CREATE INDEX CONCURRENTLY` on its own (never inside a transaction block).
A partitioned table cannot take `CREATE INDEX CONCURRENTLY` on the parent: build
the index on each partition, create the parent index with
`CREATE INDEX ... ON ONLY`, and attach every partition with
`ALTER INDEX ... ATTACH PARTITION`. An interrupted concurrent build leaves the
index `INVALID`: drop it and retry. `CREATE INDEX IF NOT EXISTS` matches the
name, not the definition, so the runbook verifies `pg_index.indisvalid`, that
the index has no predicate or expression, and that every partition is attached.

A validated foreign key is the other expensive DDL: `ADD FOREIGN KEY` runs as an
`ALTER TABLE`, so it takes `ACCESS EXCLUSIVE` on the referencing table and
`SHARE ROW EXCLUSIVE` on the referenced one, and it scans the whole referencing
table. `NOT VALID` on a partitioned table is only available since PostgreSQL 18.
A reference that a GC task resolves must be `DEFERRABLE INITIALLY IMMEDIATE`,
never `INITIALLY DEFERRED`: the immediate check fires inside the transaction
that catches the violation and retries; the deferred one fires at commit,
outside the catch.

The runbook ends by verifying the end state (column, pending rows, index and
constraint shape) and marking the migration step as applied
(`INSERT INTO migrations (module, step) ...`), so the body does not run at
startup. The marker is the only thing that skips the migration.

A backfill that removes a key an old reader uses (for example a JSONB pointer
that the previous GC scans) must run with the old code stopped. Otherwise the
old reader loses the reference and can delete a live object, and the rows it
writes in the meantime miss the backfill.

`ADD COLUMN` takes a brief `ACCESS EXCLUSIVE` even when it changes nothing. A
migration accepts that lock; a deployment that cannot take it runs the runbook
and marks the step as applied.

If you need to have a global overview of the all schema of the database you can extract it
using postgresql:

```bash
# (in the devenv environment)
pg_dump -h postgres -s  > schema.sql
```

## Linter ##

There are no watch process for the linter; you will need to execute it
manually. We use [clj-kondo][kondo] for linting purposes and the
repository already comes with base configuration.

[kondo]: https://github.com/clj-kondo/clj-kondo

You can run **clj-kondo** as-is (is included in the devenv image):

```bash
cd penpot/backend;
clj-kondo --lint src
```

