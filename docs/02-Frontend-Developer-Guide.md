# Frontend Guide #

This guide intends to explain the essential details of the frontend
application.

## Access to clojure from javascript console

The uxbox namespace of the main application is exported, so that is
accessible from javascript console in Chrome developer tools. Object
names and data types are converted to javascript style. For example
you can emit the event to reset zoom level by typing this at the
console (there is autocompletion for help):

```javascript
uxbox.main.store.emit_BANG_(uxbox.main.data.workspace.reset_zoom)
```

## Visual debug mode and utilities

Debugging a problem in the viewport algorithms for grouping and
rotating is difficult. We have set a visual debug mode that displays
some annotations on screen, to help understanding what's happening.

To activate it, open the javascript console and type

```javascript
uxbox.util.debug.toggle_debug("option")
```

Current options are `bounding-boxes`, `group`, `events` and
`rotation-handler`.

You can also activate or deactivate all visual aids with

```javascript
uxbox.util.debug.debug_all()
uxbox.util.debug.debug_none()
```

## Debug state and objects

There are also some useful functions to visualize the global state or
any complex object. To use them from clojure:

```clojure
(ns uxbox.util.debug)
(logjs <msg> <var>) ; to print the value of a variable
(tap <fn>) ; to include a function with side effect (e.g. logjs) in a transducer.

(ns uxbox.main.store)
(dump-state) ; to print in console all the global state
(dump-objects) ; to print in console all objects in workspace
```

But last ones are most commonly used from javscript console:

```
uxbox.main.store.dump_state()
uxbox.main.store.dump_objects()
```


## Icons & Assets

The icons used on the frontend application are loaded using svgsprite
(properly handled by the gulp watch task). All icons should be on SVG
format located in `resources/images/icons`. The gulp task will
generate the sprite and the embedd it into the `index.html`.

Then, you can reference the icon from the sprite using the
`uxbox.builtins.icons/icon-xref` macro:

```clojure
(ns some.namespace
  (:require-macros [uxbox.builtins.icons :refer [icon-xref]]))

(icon-xref :arrow)
```

For performance reasons, all used icons are statically defined in the
`src/uxbox/buitings/icons.cljs` file.



## Translations (I18N) ##

### How it Works ###

All the translation strings of this application are stored in
`resources/locales.json` file. It has a self explanatory format that
looks like this:

```json
{
  "auth.email-or-username" : {
    "used-in" : [ "src/uxbox/main/ui/auth/login.cljs:61" ],
    "translations" : {
      "en" : "Email or Username",
      "fr" : "adresse email ou nom d'utilisateur"
    }
  },
  "ds.num-projects" : {
    "translations": {
      "en": ["1 project", "%s projects"]
    }
  },
}
```

For development convenience, you can forget about the specific format
of that file, and just add a simple key-value entry pairs like this:

```
{
  [...],
  "foo1": "bar1",
  "foo2": "bar2"
}
```

The file is automatically bundled into the `index.html` file on
compile time (in development and production). The bundled content is a
simplified version of this data structure for avoid load unnecesary
data.

The development environment has a watch process that detect changes on
that file and recompiles the `index.html`. **There are no hot reload
for translations strings**, you just need to refresh the browser tab
for refresh the translations in the running the application.

If you have used the short (key-value) format, the watch process will
automatically convert it to the apropriate format before generate the
`index.html`.

Finally, when you have finished to adding texts, execute the following
command for reformat the file, and track the usage locations (the
"used-in" list) before commiting the file into the repository:

```bash
clojure -Adev locales.clj collect src/uxbox/main/ resources/locales.json
```

NOTE: Later, we will need to think and implement the way to export and
import to other formats (mainly for transifex and similar services
compatibility).


### How to use it ###

You have two aproaches for translate strings: one for general purpose
and other specific for React components (that leverages reactivity for
language changes).

The `uxbox.util.i18n/tr` is the general purpose function. This is a
simple use case example:

```clojure
(require '[uxbox.util.i18n :refer [tr])

(tr "auth.email-or-username")
;; => "Email or Username"
```

If you have defined plurals for some translation resource, then you
need to pass an additional parameter marked as counter in order to
allow the system know when to show the plural:

```clojure
(require '[uxbox.util.i18n :as i18n :refer [tr]])

(tr "ds.num-projects" (i18n/c 10))
;; => "10 projects"

(tr "ds.num-projects" (i18n/c 1))
;; => "1 project"
```

For React components, you have `uxbox.util.i18n/use-locale` hook
and the `uxbox.util.i18n/t` function:

```clojure
(require '[uxbox.util.i18n :as i18n :refer [t]])

(mf/defc my-component
  [props]
  (let [locale (i18n/use-locale)]
    [:div
     [:span (t locale "auth.email-or-username")]]))
```

You can use the general purpose function in React component but when
language is changed the component will not be rerendered
automatically.




