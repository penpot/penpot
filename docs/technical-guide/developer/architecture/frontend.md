---
title: Frontend app
---

### Frontend app

The main application, with the user interface and the presentation logic.

To talk with backend, it uses a custom RPC-style API: some functions in the
backend are exposed through an HTTP server. When the front wants to execute a
query or data mutation, it sends a HTTP request, containing the name of the
function to execute, and the ascii-encoded arguments. The resulting data is
also encoded and returned. This way we don't need any data type conversion,
besides the transport encoding, as there is Clojure at both ends.

When the user opens any file, a persistent websocket is opened with the backend
and associated to the file id. It is used to send presence events, such as
connection, disconnection and mouse movements. And also to receive changes made
by other users that are editing the same file, so it may be updated in real
time.

## Frontend structure

Penpot frontend app code resides under <code class="language-text">frontend/src/app</code> path in the main repository.

@startuml FrontendGeneral
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Container.puml
!define DEVICONS https://raw.githubusercontent.com/tupadr3/plantuml-icon-font-sprites/master/devicons
!include DEVICONS/react.puml

HIDE_STEREOTYPE()

Person(user, "User")
System_Boundary(frontend, "Frontend") {
    Container(frontend_app, "Frontend app", "React / ClojureScript", "", "react")
    Container(worker, "Worker", "Web worker")
}

Rel(user, frontend_app, "Uses", "HTTPS")
BiRel_L(frontend_app, worker, "Works with")

@enduml

```text
  ▾ frontend/src/app/
    ▸ main/
    ▸ util/
    ▸ worker/
      main.cljs
      worker.cljs
```

* <code class="language-text">main.cljs</code> and <code class="language-text">main/</code> contain the main frontend app, written in
  ClojureScript language and using React framework, wrapped in [rumext
  library](https://github.com/funcool/rumext).
* <code class="language-text">worker.cljs</code> and <code class="language-text">worker/</code> contain the web worker, to make expensive
  calculations in background.
* <code class="language-text">util/</code> contains many generic utilities, non dependant on the user
  interface.

@startuml FrontendMain
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Context.puml
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Component.puml

HIDE_STEREOTYPE()

Component(ui, "ui", "main web component")
Component(store, "store", "module")
Component(refs, "refs", "module")
Component(repo, "repo", "module")
Component(streams, "streams", "module")
Component(errors, "errors", "module")

Boundary(ui_namespaces, "ui namespaces") {
    Component(ui_auth, "auth", "web component")
    Component(ui_settings, "settings", "web component")
    Component(ui_dashboard, "dashboard", "web component")
    Component(ui_workspace, "workspace", "web component")
    Component(ui_viewer, "viewer", "web component")
    Component(ui_render, "render", "web component")
    Component(ui_exports, "exports", "web component")
    Component(ui_shapes, "shapes", "component library")
    Component(ui_components, "components", "component library")
}

Boundary(data_namespaces, "data namespaces") {
    Component(data_common, "common", "events")
    Component(data_users, "users", "events")
    Component(data_dashboard, "dashboard", "events")
    Component(data_workspace, "workspace", "events")
    Component(data_viewer, "viewer", "events")
    Component(data_comments, "comments", "events")
    Component(data_fonts, "fonts", "events")
    Component(data_messages, "messages", "events")
    Component(data_modal, "modal", "events")
    Component(data_shortcuts, "shortcuts", "utilities")
}

Lay_D(ui_exports, data_viewer)
Lay_D(ui_settings, ui_components)
Lay_D(data_viewer, data_common)
Lay_D(data_fonts, data_messages)
Lay_D(data_dashboard, data_modal)
Lay_D(data_workspace, data_shortcuts)
Lay_L(data_dashboard, data_fonts)
Lay_L(data_workspace, data_comments)

Rel_Up(refs, store, "Watches")
Rel_Up(streams, store, "Watches")

Rel(ui, ui_auth, "Routes")
Rel(ui, ui_settings, "Routes")
Rel(ui, ui_dashboard, "Routes")
Rel(ui, ui_workspace, "Routes")
Rel(ui, ui_viewer, "Routes")
Rel(ui, ui_render, "Routes")

Rel(ui_render, ui_exports, "Uses")
Rel(ui_workspace, ui_shapes, "Uses")
Rel(ui_viewer, ui_shapes, "Uses")
Rel_Right(ui_exports, ui_shapes, "Uses")

Rel(ui_auth, data_users, "Uses")
Rel(ui_settings, data_users, "Uses")
Rel(ui_dashboard, data_dashboard, "Uses")
Rel(ui_dashboard, data_fonts, "Uses")
Rel(ui_workspace, data_workspace, "Uses")
Rel(ui_workspace, data_comments, "Uses")
Rel(ui_viewer, data_viewer, "Uses")

@enduml

### General namespaces

* **store** contains the global state of the application. Uses an event loop
  paradigm, similar to Redux, with a global state object and a stream of events
  that modify it. Made with [potok library](https://funcool.github.io/potok/latest/).

* **refs** has the collection of references or lenses: RX streams that you can
  use to subscribe to parts of the global state, and be notified when they
  change.

* **streams** has some streams, derived from the main event stream, for keyboard
  and mouse events. Used mainly from the workspace viewport.

* **repo** contains the functions to make calls to backend.

* **errors** has functions with global error handlers, to manage exceptions or other
  kinds of errors in the ui or the data events, notify the user in a useful way,
  and allow to recover and continue working.

### UI namespaces

* **ui** is the root web component. It reads the current url and mounts the needed
  subcomponent depending on the route.

* **auth** has the web components for the login, register, password recover,
  etc. screens.

* **settings** has the web components for the user profile and settings screens.

* **dashboard** has the web components for the dashboard and its subsections.

* **workspace** has the web components for the file workspace and its subsections.

* **viewer** has the web components for the viewer and its subsections.

* **render** contain special web components to render one page or one specific
  shape, to be used in exports.

* **export** contain basic web components that display one shape or frame, to
  be used from exports render or else from dashboard and viewer thumbnails and
  other places.

* **shapes** is the basic collection of web components that convert all types of
  shapes in the corresponding svg elements, without adding any extra function.

* **components** a library of generic UI widgets, to be used as building blocks
  of penpot screens (text or numeric inputs, selects, forms, buttons...).


### Data namespaces

* **users** has events to login and register, fetch the user profile and update it.

* **dashboard** has events to fetch and modify teams, projects and files.

* **fonts** has some extra events to manage uploaded fonts from dashboard.

* **workspace** has a lot of events to manage the current file and do all kinds of
  edits and updates.

* **comments** has some extra events to manage design comments.

* **viewer** has events to fetch a file contents to display, and manage the
  interactive behavior and hand-off.

* **common** has some events used from several places.

* **modal** has some events to show modal popup windows.

* **messages** has some events to show non-modal informative messages.

* **shortcuts** has some utility functions, used in other modules to setup the
  keyboard shortcuts.


## Worker app

Some operations are costly to make in real time, so we leave them to be
executed asynchronously in a web worker. This way they don't impact the user
experience. Some of these operations are generating file thumbnails for the
dashboard and maintaining some geometric indexes to speed up snap points while
drawing.

@startuml FrontendWorker
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Context.puml
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Component.puml

HIDE_STEREOTYPE()

Component(worker, "worker", "worker entry point")

Boundary(worker_namespaces, "worker namespaces") {
    Component(thumbnails, "thumbnails", "worker methods")
    Component(snaps, "snaps", "worker methods")
    Component(selection, "selection", "worker methods")
    Component(impl, "impl", "worker methods")
    Component(import, "import", "worker methods")
    Component(export, "export", "worker methods")
}

Rel(worker, thumbnails, "Uses")
Rel(worker, impl, "Uses")
Rel(worker, import, "Uses")
Rel(worker, export, "Uses")
Rel(impl, snaps, "Uses")
Rel(impl, selection, "Uses")

@enduml

* **worker** contains the worker setup code and the global handler that receives
  requests from the main app, and process them.

* **thumbnails** has a method to generate the file thumbnails used in dashboard.

* **snaps** manages a distance index of shapes, and has a method to get
  other shapes near a given one, to be used in snaps while drawing.

* **selection** manages a geometric index of shapes, with methods to get what
  shapes are under the cursor at a given moment, for select.

* **impl** has a simple method to update all indexes in a page at once.

* **import** has a method to import a whole file from an external <code class="language-text">.penpot</code> archive.

* **export** has a method to export a whole file to an external <code class="language-text">.penpot</code> archive.

