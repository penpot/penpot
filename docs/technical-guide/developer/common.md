---
title: 3.04. Common Guide
desc: "View Penpot's technical guide: self-hosting, configuration, developer insights, architecture, data model, integration, and troubleshooting."
---

# Common guide

This section has articles related to all submodules (frontend, backend and
exporter) such as: code style hints, architecture decisions, etc...


## Configuration

Both in the backend, the frontend and the exporter subsystems, there are an
<code class="language-text">app.config</code> namespace that defines the global configuration variables,
their specs and the default values.

All variables have a conservative default, meaning that you can set up a Penpot
instance without changing any configuration, and it will be reasonably safe
and useful.

In backend and exporter, to change the runtime values you need to set them in
the process environment, following the rule that an environment variable in the
form <code class="language-bash">PENPOT_<VARIABLE_NAME_IN_UPPERCASE></code> correspond to a configuration
variable named <code class="language-bash">variable-name-in-lowercase</code>. Example:

```bash
(env)
PENPOT_ASSETS_STORAGE_BACKEND=assets-s3

(config)
assets-storage-backend :assets-s3
```

In frontend, the main <code class="language-text">resources/public/index.html</code> file includes (if it
exists) a file named <code class="language-text">js/config.js</code>, where you can set configuration values
as javascript global variables. The file is not created by default, so if
you need it you must create it blank, and set the variables you want, in
the form <code class="language-bash">penpot\<VariableNameInCamelCase></code>:

```js
(js/config.js)
var penpotPublicURI = "https://penpot.example.com";

(config)
public-uri "https://penpot.example.com"
```

### On premise instances

If you use the official Penpot docker images, as explained in the [Getting
Started](/technical-guide/getting-started/#start-penpot) section, there is a
[config.env](https://github.com/penpot/penpot/blob/develop/docker/images/config.env)
file that sets the configuration environment variables. It's the same file for
backend, exporter and frontend.

For this last one, there is a script
[nginx-entrypoint.sh](https://github.com/penpot/penpot/blob/develop/docker/images/files/nginx-entrypoint.sh)
that reads the environment and generates the <code class="language-text">js/config.js</code> when the container
is started. This way all configuration is made in the single <code class="language-text">config.env</code> file.


### Dev environment

If you use the [developer docker images](/technical-guide/developer/devenv/),
the [docker-compose.yaml](https://github.com/penpot/penpot/blob/develop/docker/devenv/docker-compose.yaml)
directly sets the environment variables more appropriate for backend and
exporter development.

Additionally, the backend [start script](https://github.com/penpot/penpot/blob/develop/backend/scripts/start-dev)
and [repl script](https://github.com/penpot/penpot/blob/develop/backend/scripts/repl) set
some more variables.

The frontend uses only the defaults.

If you want to change any variable for your local environment, you can change
<code class="language-text">docker-compose.yaml</code> and shut down and start again the container. Or you can
modify the start script or directly set the environment variable in your
session, and restart backend or exporter processes.

For frontend, you can manually create <code class="language-text">resources/public/js/config.js</code> (it's
ignored in git) and define your settings there. Then, just reload the page.

## System logging

In [app.common.logging](https://github.com/penpot/penpot/blob/develop/common/src/app/common/logging.cljc)
we have a general system logging utility, that may be used throughout all our
code to generate execution traces, mainly for debugging.

You can add a trace anywhere, specifying the log level (<code class="language-text">trace</code>, <code class="language-text">debug</code>,
<code class="language-text">info</code>, <code class="language-text">warn</code>, <code class="language-text">error</code>) and any number of key-values:

```clojure
(ns app.main.data.workspace.libraries-helpers
  (:require [app.common.logging :as log]))

(log/set-level! :warn)

...

(defn generate-detach-instance
  [changes container shape-id]
  (log/debug :msg "Detach instance"
             :shape-id shape-id
             :container (:id container))
  ...)
```

The current namespace is tracked within the log message, and you can configure
at runtime, by namespace, the log level (by default <code class="language-clojure">:warn</code>). Any trace below
this level will be ignored.

Some keys have a special meaning:
 * <code class="language-clojure">:msg</code> is the main trace message.
 * <code class="language-clojure">::log/raw</code> outputs the value without any processing or prettifying.
 * <code class="language-clojure">::log/context</code> append metadata to the trace (not printed, it's to be
   processed by other tools).
 * <code class="language-clojure">::log/cause</code> (only in backend) attach a java exception object that will
   be printed in a readable way with the stack trace.
 * <code class="language-clojure">::log/async</code> (only in backend) if set to false, makes the log processing
   synchronous. If true (the default), it's executed in a separate thread.
 * <code class="language-clojure">:js/\<key></code> (only in frontend) if you prefix the key with the <code class="language-text">js/</code>
   namespace, the value will be printed as a javascript interactively
   inspectionable object.
 * <code class="language-clojure">:err</code> (only in frontend) attach a javascript exception object, and it
   will be printed in a readable way with the stack trace.

### backend

The logging utility uses a different library for Clojure and Clojurescript. In
the first case we use [log4j2](https://logging.apache.org/log4j/2.x) to have
much flexibility.

The configuration is made in [log4j2.xml](https://github.com/penpot/penpot/blob/develop/backend/resources/log4j2.xml)
file. The Logger used for this is named "app" (there are other loggers for
other subsystems). The default configuration just outputs all traces of level
<code class="language-clojure">debug</code> or higher to the console standard output.

There is a different [log4j2-devenv](https://github.com/penpot/penpot/blob/develop/backend/resources/log4j2-devenv.xml)
for the development environment. This one outputs traces of level <code class="language-text">trace</code> or
higher to a file, and <code class="language-text">debug</code> or higher to a <code class="language-text">zmq</code> queue, that may be
subscribed for other parts of the application for further processing.

The ouput for a trace in <code class="language-text">logs/main.log</code> uses the format

```bash
[<date time>] : <level> <namespace> - <key1=val1> <key2=val2> ...
```

Example:

```bash
[2022-04-27 06:59:08.820] T app.rpc - action="register", name="update-file"
```

The <code class="language-text">zmq</code> queue is not used in the default on premise or devenv setups, but there
are a number of handlers you can use in custom instances to save errors in the
database, or send them to a [Sentry](https://sentry.io/welcome/) or similar
service, for example.

### frontend and exporter

In the Clojurescript subservices, we use [goog.log](https://google.github.io/closure-library/api/goog.log.html)
library. This is much simpler, and basically outputs the traces to the console
standard output (the devtools in the browser or the console in the nodejs
exporter).

In the browser, we have an utility [debug function](/technical-guide/developer/frontend/#console-debug-utility)
that enables you to change the logging level of any namespace (or of the whole
app) in a live environment:

```javascript
debug.set_logging("namespace", "level")
```

## Assertions

Penpot source code has this types of assertions:

### **assert**

Just using the clojure builtin `assert` macro.

Example:

```clojure
(assert (number? 3) "optional message")
```

This asserts are only executed in development mode. In production
environment all asserts like this will be ignored by runtime.

### **spec/assert**

Using the <code class="language-text">app.common.spec/assert</code> macro.

This macro is based in <code class="language-text">cojure.spec.alpha/assert</code> macro, and it's
also ignored in a production environment.

The Penpot variant doesn't have any runtime checks to know if asserts
are disabled. Instead, the assert calls are completely removed by the
compiler/runtime, thus generating simpler and faster code in production
builds.

Example:

```clojure
(require '[clojure.spec.alpha :as s]
         '[app.common.spec :as us])

(s/def ::number number?)

(us/assert ::number 3)
```

### **spec/verify**

An assertion type that is always executed.

Example:

```clojure
(require '[app.common.spec :as us])

(us/verify ::number 3)
```

This macro enables you to have assertions on production code, that
generate runtime exceptions when failed (make sure you handle them
appropriately).

## Unit tests

We expect all Penpot code (either in frontend, backend or common subsystems) to
have unit tests, i.e. the ones that test a single unit of code, in isolation
from other blocks. Currently we are quite far from that objective, but we are
working to improve this.

### Running tests with kaocha

Unit tests are executed inside the [development environment](/technical-guide/developer/devenv).

We can use [kaocha test runner](https://cljdoc.org/d/lambdaisland/kaocha/), and
we have prepared, for convenience, some aliases in <code class="language-text">deps.edn</code> files. To run
them, just go to <code class="language-text">backend</code>, <code class="language-text">frontend</code> or <code class="language-text">common</code> and execute:

```bash
# To run all tests once
clojure -M:dev:test

# To run all tests and keep watching for changes
clojure -M:dev:test --watch

# To run a single tests module
clojure -M:dev:test --focus common-tests.logic.comp-sync-test

# To run a single test
clojure -M:dev:test --focus common-tests.logic.comp-sync-test/test-sync-when-changing-attribute
```

Watch mode runs all tests when some file changes, except if some tests failed
previously. In this case it only runs the failed tests. When they pass, then
runs all of them again.

You can also mark tests in the code by adding metadata:

```clojure
;; To skip a test, for example when is not working or too slow
(deftest ^:kaocha/skip bad-test
  (is (= 2 1)))

;; To skip it but warn you during test run, so you don't forget it
(deftest ^:kaocha/pending bad-test
  (is (= 2 1)))
```

Please refer to the [kaocha manual](https://cljdoc.org/d/lambdaisland/kaocha/1.91.1392/doc/6-focusing-and-skipping)
for how to define custom metadata and other ways of selecting tests.

**NOTE**: in <code class="language-text">frontend</code> we still can't use kaocha to run the tests. We are on
it, but for now we use shadow-cljs with <code class="language-text">package.json</code> scripts:

```bash
yarn run test
yarn run test:watch
```

#### Test output

The default kaocha reporter outputs a summary for the test run. There is a pair
of brackets <code class="language-bash">[ ]</code> for each suite, a pair of parentheses <code class="language-bash">( )</code> for each test,
and a dot <code class="language-bash">.</code> for each assertion <code class="language-bash">t/is</code> inside tests.

```bash
penpot@c261c95d4623:~/penpot/common$ clojure -M:dev:test
[(...)(............................................................
.............................)(....................................
..)(..........)(.................................)(.)(.............
.......................................................)(..........
.....)(......)(.)(......)(.........................................
..............................................)(............)]
190 tests, 3434 assertions, 0 failures.
```

All standard output from the tests is captured and hidden, except if some test
fails. In this case, the output for the failing test is shown in a box:

```bash
FAIL in sample-test/stdout-fail-test (sample_test.clj:10)
Expected:
  :same
Actual:
  -:same +:not-same
╭───── Test output ───────────────────────────────────────────────────────
│ Can you see this?
╰─────────────────────────────────────────────────────────────────────────
2 tests, 2 assertions, 1 failures.
```

You can bypass the capture with the command line:

```bash
clojure -M:dev:test --no-capture-output
```

Or for some specific output:

```clojure
(ns sample-test
  (:require [clojure.test :refer :all]
            [kaocha.plugin.capture-output :as capture]))

(deftest stdout-pass-test
  (capture/bypass
    (println "This message should be displayed"))
  (is (= :same :same)))
```

### Running tests in the REPL

An alternative way of running tests is to do it from inside the
[REPL](/technical-guide/developer/backend/#repl) you can use in the backend and
common apps in the development environment.

We have a helper function <code class="language-bash">(run-tests)</code> that refreshes the environment (to avoid
having [stale tests](https://practical.li/clojure/testing/unit-testing/#command-line-test-runners))
and runs all tests or a selection. It is defined in <code class="language-bash">backend/dev/user.clj</code> and
<code class="language-text">common/dev/user.clj</code>, so it's available without importing anything.

First start a REPL:

```bash
~/penpot/backend$ scripts/repl
```

And then:

```clojure
;; To run all tests
(run-tests)

;; To run all tests in one namespace
(run-tests 'some.namespace)

;; To run a single test
(run-tests 'some.namespace/some-test)

;; To run all tests in one or several namespaces,
;; selected by a regular expression
(run-tests #"^backend-tests.rpc.*")
```

### Writing unit tests

We write tests using the standard [Clojure test
API](https://clojure.github.io/clojure/clojure.test-api.html). You can find a
[guide to writing unit tests](https://practical.li/clojure/testing/unit-testing) at Practicalli
Clojure, that we follow as much as possible.

#### Sample files helpers

An important issue when writing tests in Penpot is to have files with the
specific configurations we need to test. For this, we have defined a namespace
of helpers to easily create files and its elements with sample data.

To make handling of uuids more convenient, those functions have a uuid
registry. Whenever you create an object, you may give a <code class="language-clojure">:label</code>, and the id of
the object will be stored in the registry associated with this label, so you
can easily recover it later.

You have functions to create files, pages and shapes, to connect them and
specify their attributes, having all of them default values if not set.

Files also store in metadata the **current page**, so you can control in what
page the <code class="language-clojure">add-</code> and <code class="language-clojure">get-</code> functions will operate.

```clojure
(ns common-tests.sample-helpers-test
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/deftest test-create-file
  (let [;; Create a file with one page
        f1 (thf/sample-file :file1)

        ;; Same but define the label of the page, to retrieve it later
        f2 (thf/sample-file :file2 :page-label :page1)

        ;; Set the :name attribute of the created file
        f3 (thf/sample-file :file3 :name "testing file")

        ;; Create an isolated page
        p2 (thf/sample-page :page2 :name "testing page")

        ;; Create a second page and add to the file
        f4 (-> (thf/sample-file :file4 :page-label :page3)
               (thf/add-sample-page :page4 :name "other testing page"))

        ;; Create an isolated shape
        p2 (thf/sample-shape :shape1 :type :rect :name "testing shape")

        ;; Add a couple of shapes to a previous file, in different pages
        f5 (-> f4
               (ths/add-sample-shape :shape2)
               (thf/switch-to-page :page4)
               (ths/add-sample-shape :shape3 :name "other testing shape"
                                     :width 100))

        ;; Retrieve created shapes
        s1 (ths/get-shape f4 :shape1)
        s2 (ths/get-shape f5 :shape2 :page-label :page3)
        s3 (ths/get-shape f5 :shape3)]

    ;; Check some values
    (t/is (= (:name f1) "Test file"))
    (t/is (= (:name f3) "testing file"))
    (t/is (= (:id f2) (thi/id :file2)))
    (t/is (= (:id (thf/current-page f2)) (thi/id :page1)))
    (t/is (= (:id s1) (thi/id :shape1)))
    (t/is (= (:name s1) "Rectangle"))
    (t/is (= (:name s3) "testing shape"))
    (t/is (= (:width s3) 100))
    (t/is (= (:width (:selrect s3)) 100))))
```

Also there are functions to make some transformations, like creating a
component, instantiating it or swapping a copy.

```clojure
(ns app.common-tests.sample-components-test
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.shapes :as ths]))

(t/deftest test-create-component
  (let [;; Create a file with one component
        f1 (-> (thf/sample-file :file1)
               (ths/add-sample-shape :frame1 :type :frame)
               (ths/add-sample-shape :rect1 :type :rect
                                     :parent-label :frame1)
               (thc/make-component :component1 :frame1))]))
```

Finally, there are composition helpers, to build typical structures with a
single line of code. And the files module has some functions to display the
contents of a file, in a way similar to `debug/dump-tree` but showing labels
instead of ids:

```clojure
(ns app.common-tests.sample-compositions-test
  (:require
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]))

(t/deftest test-create-composition
  (let [f1 (-> (thf/sample-file :file1)
               (tho/add-simple-component-with-copy :component1
                                                   :main-root
                                                   :main-child
                                                   :copy-root))]
  (ctf/dump-file f1 :show-refs? true)))

;; {:main-root} [:name Frame1] # [Component :component1]
;;   :main-child [:name Rect1]
;;
;; :copy-root [:name Frame1]   #--> [Component :component1] :main-root
;;   <no-label> [:name Rect1]  ---> :main-child
```

You can see more examples of usage by looking at the existing unit tests.

