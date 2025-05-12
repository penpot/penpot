---
title: Exporter app
desc: Learn about self-hosting, configuration, architecture (backend, frontend), data model, and development environment. See Penpot's technical guide.
---

# Exporter app

When exporting file contents to a file, we want the result to be exactly the
same as the user sees in screen. To achieve this, we use a headless browser
installed in the backend host, and controled via puppeteer automation. The
browser loads the frontend app from the static webserver, and executes it like
a normal user browser. It visits a special endpoint that renders one shape
inside a file. Then, if takes a screenshot if we are exporting to a bitmap
image, or extract the svg from the DOM if we want a vectorial export, and write
it to a file that the user can download.

## Exporter structure

Penpot exporter app code resides under <code class="language-text">exporter/src/app</code> path in the main repository.

@startuml Exporter
!include https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Container.puml
!define DEVICONS https://raw.githubusercontent.com/tupadr3/plantuml-icon-font-sprites/master/devicons
!include DEVICONS/react.puml
!include DEVICONS/clojure.puml
!include DEVICONS/chrome.puml

HIDE_STEREOTYPE()

Container(frontend_app, "Frontend app", "React / ClojureScript", "", "react")

System_Boundary(backend, "Backend") {
    Container(exporter, "Exporter", "ClojureScript / nodejs", "", "clojure")
    Container(browser, "Headless browser", "Chrome", "", "chrome")
}

Rel_D(frontend_app, exporter, "Uses", "HTTPS")
Rel_R(exporter, browser, "Uses", "puppeteer")
Rel_U(browser, frontend_app, "Uses", "HTTPS")

@enduml

```text
  ▾ exporter/src/app/
    ▸ http/
    ▸ renderer/
    ▸ util/
      core.cljs
      http.cljs
      browser.cljs
      config.cljs
```

## Exporter namespaces

* **core** has the setup and run functions of the nodejs app.

* **http** exposes a basic http server, with endpoints to export a shape or a
  file.

* **browser** has functions to control a local Chromium browser via
  [puppeteer](https://puppeteer.github.io/puppeteer).

* **renderer** has functions to tell the browser to render an object and make a
  screenshot, and then convert it to bitmap, pdf or svg as needed.

* **config** gets configuration settings from the linux environment.

* **util** has some generic utility functions.
