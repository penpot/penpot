---
title: Backend app
---

# Backend app

This app is in charge of CRUD of data, integrity validation and persistence
into a database and also into a file system for media attachments.

To handle deletions it uses a garbage collector mechanism: no object in the
database is deleted instantly. Instead, a field `deleted_at` is set with the
date and time of the deletion, and every query ignores db rows that have this
field set. Then, an async task that runs periodically, locates rows whose
deletion date is older than a given threshold and permanently deletes them.

For this, and other possibly slow tasks, there is an internal async tasks
worker, that may be used to queue tasks to be scheduled and executed when the
backend is idle. Other tasks are email sending, collecting data for telemetry
and detecting unused media attachment, for removing them from the file storage.

## Backend structure

Penpot backend app code resides under `backend/src/app` path in the main repository.

@startuml BackendGeneral
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Container.puml
!define DEVICONS https://raw.githubusercontent.com/tupadr3/plantuml-icon-font-sprites/master/devicons
!include DEVICONS/react.puml
!include DEVICONS/java.puml
!include DEVICONS/clojure.puml
!include DEVICONS/postgresql.puml
!include DEVICONS/redis.puml
!include DEVICONS/chrome.puml

HIDE_STEREOTYPE()

Container(frontend_app, "Frontend app", "React / ClojureScript", "", "react")

System_Boundary(backend, "Backend") {
    Container(backend_app, "Backend app", "Clojure / JVM", "", "clojure")
    ContainerDb(db, "Database", "PostgreSQL", "", "postgresql")
    ContainerDb(redis, "Broker", "Redis", "", "redis")
}

BiRel(frontend_app, backend_app, "Open", "websocket")
Rel(frontend_app, backend_app, "Uses", "RPC API")
Rel(backend_app, db, "Uses", "SQL")
Rel(redis, backend_app, "Subscribes", "pub/sub")
Rel(backend_app, redis, "Notifies", "pub/sub")

@enduml

```
  ▾ backend/src/app/
    ▸ cli/
    ▸ http/
    ▸ migrations/
    ▸ rpc/
    ▸ setup/
    ▸ srepl/
    ▸ util/
    ▸ tasks/
      main.clj
      config.clj
      http.clj
      metrics.clj
      migrations.clj
      notifications.clj
      rpc.clj
      setup.clj
      srepl.clj
      worker.clj
      ...
```

* `main.clj` defines the app global settings and the main entry point of the
  application, served by a JVM.
* `config.clj` defines of the configuration options read from linux
  environment.
* `http` contains the HTTP server and the backend routes list.
* `migrations` contains the SQL scripts that define the database schema, in
  the form of a sequence of migrations.
* `rpc` is the main module to handle the RPC API calls.
* `notifications.clj` is the main module that manages the websocket. It allows
  clients to subscribe to open files, intercepts update RPC calls and notify
  them to all subscribers of the file.
* `setup` initializes the environment (loads config variables, sets up the
  database, executes migrations, loads initial data, etc).
* `srepl` sets up an interactive REPL shell, with some useful commands to be
  used to debug a running instance.
* `cli` sets a command-line interface, with some more maintenance commands.
* `metrics.clj` has some interceptors that watches RPC calls, calculate
  statistics and other metrics, and send them to external systems to store and
  analyze.
* `worker.clj` and `tasks` define some async tasks that are executed in
  parallel to the main http server (using java threads), and scheduled in a
  cron-like table. They are useful to do some garbage collection, data packing
  and similar periodic maintenance tasks.
* `db.clj`, `emails.clj`, `media.clj`, `msgbus.clj`, `storage.clj`,
  `rlimits.clj` are general libraries to use I/O resources (SQL database,
  send emails, handle multimedia objects, use REDIS messages, external file
  storage and semaphores).
* `util/` has a collection of generic utility functions.

### RPC calls

The RPC (Remote Procedure Call) subsystem consists of a mechanism that allows
to expose clojure functions as an HTTP endpoint. We take advantage of being
using Clojure at both front and back ends, to avoid needing complex data
conversions.

  1. Frontend initiates a "query" or "mutation" call to `:xxx` method, and
     passes a Clojure object as params.
  2. Params are string-encoded using
     [transit](https://github.com/cognitect/transit-clj), a format similar to
     JSON but more powerful.
  3. The call is mapped to `<backend-host>/api/rpc/query/xxx` or
     `<backend-host>/api/rpc/mutation/xxx`.
  4. The `rpc` module receives the call, decode the parameters and executes the
     corresponding method inside `src/app/rpc/queries/` or `src/app/rpc/mutations/`.
     We have created a `defmethod` macro to declare an RPC method and its
     parameter specs.
  5. The result value is also transit-encoded and returned to the frontend.

This way, frontend can execute backend calls like it was calling an async function,
with all the power of Clojure data structures.

### PubSub

To manage subscriptions to a file, to be notified of changes, we use a redis
server as a pub/sub broker. Whenever a user visits a file and opens a
websocket, the backend creates a subscription in redis, with a topic that has
the id of the file. If the user sends any change to the file, backend sends a
notification to this topic, that is received by all subscribers. Then the
notification is retrieved and sent to the user via the websocket.

