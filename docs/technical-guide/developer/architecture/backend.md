---
title: Backend app
---

# Backend app

This app is in charge of CRUD of data, integrity validation and persistence
into a database and also into a file system for media attachments.

To handle deletions it uses a garbage collector mechanism: no object in the
database is deleted instantly. Instead, a field <code class="language-bash">deleted_at</code> is set with the
date and time of the deletion, and every query ignores db rows that have this
field set. Then, an async task that runs periodically, locates rows whose
deletion date is older than a given threshold and permanently deletes them.

For this, and other possibly slow tasks, there is an internal async tasks
worker, that may be used to queue tasks to be scheduled and executed when the
backend is idle. Other tasks are email sending, collecting data for telemetry
and detecting unused media attachment, for removing them from the file storage.

## Backend structure

Penpot backend app code resides under <code class="language-text">backend/src/app</code> path in the main repository.

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

* <code class="language-text">main.clj</code> defines the app global settings and the main entry point of the
  application, served by a JVM.
* <code class="language-text">config.clj</code> defines of the configuration options read from linux
  environment.
* <code class="language-text">http</code> contains the HTTP server and the backend routes list.
* <code class="language-text">migrations</code> contains the SQL scripts that define the database schema, in
  the form of a sequence of migrations.
* <code class="language-text">rpc</code> is the main module to handle the RPC API calls.
* <code class="language-text">notifications.clj</code> is the main module that manages the websocket. It allows
  clients to subscribe to open files, intercepts update RPC calls and notify
  them to all subscribers of the file.
* <code class="language-text">setup</code> initializes the environment (loads config variables, sets up the
  database, executes migrations, loads initial data, etc).
* <code class="language-text">srepl</code> sets up an interactive REPL shell, with some useful commands to be
  used to debug a running instance.
* <code class="language-text">cli</code> sets a command-line interface, with some more maintenance commands.
* <code class="language-text">metrics.clj</code> has some interceptors that watches RPC calls, calculate
  statistics and other metrics, and send them to external systems to store and
  analyze.
* <code class="language-text">worker.clj</code> and <code class="language-text">tasks</code> define some async tasks that are executed in
  parallel to the main http server (using java threads), and scheduled in a
  cron-like table. They are useful to do some garbage collection, data packing
  and similar periodic maintenance tasks.
* <code class="language-text">db.clj</code>, <code class="language-text">emails.clj</code>, <code class="language-text">media.clj</code>, <code class="language-text">msgbus.clj</code>, <code class="language-text">storage.clj</code>,
  <code class="language-text">rlimits.clj</code> are general libraries to use I/O resources (SQL database,
  send emails, handle multimedia objects, use REDIS messages, external file
  storage and semaphores).
* <code class="language-text">util/</code> has a collection of generic utility functions.

### RPC calls

The RPC (Remote Procedure Call) subsystem consists of a mechanism that allows
to expose clojure functions as an HTTP endpoint. We take advantage of being
using Clojure at both front and back ends, to avoid needing complex data
conversions.

  1. Frontend initiates a "query" or "mutation" call to <code class="language-text">:xxx</code> method, and
     passes a Clojure object as params.
  2. Params are string-encoded using
     [transit](https://github.com/cognitect/transit-clj), a format similar to
     JSON but more powerful.
  3. The call is mapped to <code class="language-text"><backend-host>/api/rpc/query/xxx</code> or
     <code class="language-text"><backend-host>/api/rpc/mutation/xxx</code>.
  4. The <code class="language-text">rpc</code> module receives the call, decode the parameters and executes the
     corresponding method inside <code class="language-text">src/app/rpc/queries/</code> or <code class="language-text">src/app/rpc/mutations/</code>.
     We have created a <code class="language-text">defmethod</code> macro to declare an RPC method and its
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

