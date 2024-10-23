---
title: 3.6. Backend Guide
---

# Backend guide #

This guide intends to explain the essential details of the backend
application.


## REPL ##

In the devenv environment you can execute `scripts/repl` to open a
Clojure interactive shell ([REPL](https://codewith.mu/en/tutorials/1.0/repl)).

Once there, you can execute `(restart)` to load and execute the backend
process, or to reload it after making changes to the source code.

Then you have access to all backend code. You can import and use any function
o read any global variable:

```clojure
(require '[app.some.namespace :as some])
(some/your-function arg1 arg2)
```

There is a specific namespace `app.srepl` with some functions useful to be
executed from the repl and perform some tasks manually. Most of them accept
a `system` parameter. There is a global variable with this name, that contains
the runtime information and configuration needed for the functions to run.

For example:

```clojure
(require '[app.srepl.main :as srepl])
(srepl/send-test-email! system "test@example.com")
```


## Fixtures ##

This is a development feature that allows populate the database with a
good amount of content (usually used for just test the application or
perform performance tweaks on queries).

In order to load fixtures, enter to the REPL environment with the `scripts/repl`
script, and then execute `(app.cli.fixtures/run {:preset :small})`.

You also can execute this as a standalone script with:

```bash
clojure -Adev -X:fn-fixtures
```

NOTE: It is an optional step because the application can start with an
empty database.

This by default will create a bunch of users that can be used to login
in the application. All users uses the following pattern:

- Username: `profileN@example.com`
- Password: `123123`

Where `N` is a number from 0 to 5 on the default fixture parameters.


## Migrations ##

The database migrations are located in two directories:

- `src/app/migrations` (contains migration scripts in clojure)
- `src/app/migrations/sql` (contains the pure SQL migrations)

The SQL migration naming consists in the following:

```
XXXX-<add|mod|del|drop|[...verb...]>-<table-name>-<any-additional-text>
```

Examples:

```
0025-del-generic-tokens-table
0026-mod-profile-table-add-is-active-field
```

**NOTE**: if table name has more than one word, we still use `-` as a separator.

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

