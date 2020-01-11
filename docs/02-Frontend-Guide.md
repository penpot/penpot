# Frontend Guide #

This guide intends to explain the essential details of the frontend
application.

**TODO**


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
clojure -Adev translations.clj collectmessages src/uxbox/main/ resources/locales.json
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
(require '[uxbox.util.i18n :as i18n])

(i18n/tr "auth.email-or-username")
;; => "Email or Username"
```

If you have defined plurals for some translation resource, then you
need to pass an additional parameter marked as counter in order to
allow the system know when to show the plural:

```clojure
(require '[uxbox.util.i18n :as i18n])

(i18n/tr "ds.num-projects" (i18n/c 10))
;; => "10 projects"

(i18n/tr "ds.num-projects" (i18n/c 1))
;; => "1 project"
```

For React components, you have `uxbox.util.i18n/use-translations` hook:

```clojure
(mf/defc my-component
  [props]
  (let [tr (i18n/use-translations)]
    [:div
     [:span (tr "auth.email-or-username")]]))
```

You can use the general purpose function in React component but when
language is changed the component will not be rerendered
automatically.




