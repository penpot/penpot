---
title: 3.01. Architecture
desc: Dive into architecture, backend, frontend, data models, and development environments. Contribute and self-host for free! See Penpot's technical guide.
---

# Architecture

This section gives an overall structure of the system.

Penpot has the architecture of a typical SPA. There is a frontend application,
written in ClojureScript and using React framework, and served from a static
web server. It talks to a backend application, that persists data on a
PostgreSQL database.

The backend is written in Clojure, so front and back can share code and data
structures without problem. Then, the code is compiled into JVM bytecode and
run in a JVM environment.

There are some additional components, explained in subsections.

@startuml C4_Elements
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Container.puml
!define DEVICONS https://raw.githubusercontent.com/tupadr3/plantuml-icon-font-sprites/master/devicons
!include DEVICONS/react.puml
!include DEVICONS/java.puml
!include DEVICONS/clojure.puml
!include DEVICONS/postgresql.puml
!include DEVICONS/redis.puml
!include DEVICONS/chrome.puml

HIDE_STEREOTYPE()

Person(user, "User")
System_Boundary(frontend, "Frontend") {
    Container(frontend_app, "Frontend app", "React / ClojureScript", "", "react")
    Container(worker, "Worker", "Web worker")
}

System_Boundary(backend, "Backend") {
    Container(backend_app, "Backend app", "Clojure / JVM", "", "clojure")
    ContainerDb(db, "Database", "PostgreSQL", "", "postgresql")
    ContainerDb(redis, "Broker", "Redis", "", "redis")
    Container(exporter, "Exporter", "ClojureScript / nodejs", "", "clojure")
    Container(browser, "Headless browser", "Chrome", "", "chrome")
}

Rel(user, frontend_app, "Uses", "HTTPS")
BiRel_L(frontend_app, worker, "Works with")
BiRel(frontend_app, backend_app, "Open", "websocket")
Rel(frontend_app, backend_app, "Uses", "RPC API")
Rel(backend_app, db, "Uses", "SQL")
Rel(redis, backend_app, "Subscribes", "pub/sub")
Rel(backend_app, redis, "Notifies", "pub/sub")
Rel(frontend_app, exporter, "Uses", "HTTPS")
Rel(exporter, browser, "Uses", "puppeteer")
Rel(browser, frontend_app, "Uses", "HTTPS")

@enduml

See more at

 * [Frontend app](/technical-guide/developer/architecture/frontend/)
 * [Backend app](/technical-guide/developer/architecture/backend/)
 * [Exporter app](/technical-guide/developer/architecture/exporter/)
 * [Common code](/technical-guide/developer/architecture/common/)

