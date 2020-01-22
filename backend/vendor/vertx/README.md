# vertx-clojure

A lightweight clojure adapter for vertx toolkit.

- **STATUS**: *alpha*, in design and prototyping phase.
- **AUDIENCE**: this is not a vertx documentation, this readme intends
  to explain only the clojure api

Example code on `resources/user.clj` file.


## Install

Using `deps.edn`:

```clojure
vertx-clojure/vertx {:mvn/version "0.0.0-SNAPSHOT"}
```

Using Leiningen:

```clojure
[vertx-clojure/vertx "0.0.0-SNAPSHOT"]
```

## User Guide


### Verticles

Verticles is the basic "unit of execution" in the vertx toolkit. The
concept is very simular to actors with the exception that a verticle
does not have a inbox, and verticles communicates with other verticles
or with the rest of the world using **eventbus**.

For create a verticle, you will need to create first a system instance
(a name that we give to the `Vertx` instance):

```clojure
(require '[vertx.core :as vc])

(def system (vc/system))
```

Then, you can proceed to create a verticle. A verticle concist on
three functions: `on-start`, `on-stop` and `on-error` (where the
`on-start` is mandatory the rest optional).

Lets define a dummy verticle that just prints hello world on start
callback:

```clojure
(defn on-start
  [ctx]
  (println "Hello world"))

(def dummy-verticle
  (vc/verticle {:on-start on-start}))
```

The `dummy-verticle` is a verticle factory, nothing is running at this
momment. For run the verticle we need to deploy it using the
previously created `system` instance:

```clojure
(vc/deploy! system dummy-verticle)
```

The `deploy!` return value is a `CompletionStage` so you can deref it
like a regular `Future` or use **funcool/promesa** for chain more
complex transformations. The return value implements `AutoCloseable`
that will allow to undeploy the verticle.

The `deploy!` function also accepts an additional parameter for
options, and at this momment it only accepts as single option:

- `:instances` - number of instances to launch of the same verticle.


### Event Bus

The **eventbus** is the central communication system for verticles. It
has different patterns of communication. On this documentation we will
cover the: `publish/subscribe` and `request/reply`.

Lets define a simple echo verticle:

```clojure
(require '[vertx.eventbus :as ve])

(defn on-message
  [msg]
  (:body msg))

(defn on-start
  [ctx]
  (vc/consumer ctx "test.echo" on-message))

(def echo-verticle
  (vc/verticle {:on-start on-start}))
```

And then, lets deploy 4 instances of it:

```clojure
(vc/deploy! system echo-verticle {:instances 4})
```

Now, depending on how you send the messages to the "test.echo" topic,
the message will be send to a single instance of will be broadcasted
to all verticle instances subscribed to it.

To send a message and expect a response we need to use the
`ve/request!` function:

```clojure
@(ve/request! system {:foo "bar"})
;; => #vertx.eventbus.Msg{:body {:foo "bar"}}
```

The return value of `on-message` callback will be used as a reply and
it can be any plain value or a `CompletionStage`.

When you want to send a message but you don't need the return value,
there is the `ve/send!` function. And finally, if you want to send a
message to all instances subscribed to a topic, you will need to use
the `ve/publish!` function.


### Http Server (vertx.http)

**STATUS**: pre-alpha: experimental & incomplete

This part will explain the low-level api for the http server. It is
intended to be used as a building block for a more higher-level api or
when you know that you exactly need for a performance sensitive
applications.

The `vertx.http` exposes two main functions `handler` and
`server`. Lets start creating a simple "hello world" http server:

```
(require '[vertx.http :as vh])

(defn hello-world-handler
  [req]
  {:status 200
   :body "Hello world\n"})

(defn on-start
  [ctx]
  (vh/server {:handler (vh/handler hello-world-handler)
              :port 2021}))

(->> (vc/verticle {:on-start on-start})
     (vc/deploy! system))
```

NOTE: you can start the server without creating a verticle but you
will loss the advantage of scaling (using verticle instances
parameter).

The `req` object is a plain map with the following keys:

- `:method` the HTTP method.
- `:path` the PATH of the requested URI.
- `:headers` a map with string lower-cased keys of headers.
- `:vertx.http/request` the underlying vertx request instance.
- `:vertx.http/response` the underlying vertx response instance.

And the response object to the ring response, it can contain
`:status`, `:body` and `:headers`.


**WARNING:** at this moment there are no way to obtain directly the
body of request using clojure api, this is in **design** phase and we
need to think how to expose it correctly without constraint too much
the user code (if you have suggestions, please open an issue).

**NOTE**: If you want completly bypass the clojure api, pass a vertx
`Handler` instance to server instead of using
`vertx.http/handler`. There is the `vertx.util/fn->handler` helper
that converts a plain clojure function into raw `Handler` instance.


### Web Server (vertx.web)

**STATUS**: alpha

This part will explain the higher-level http/web server api. It is a
general purpose with more clojure friendly api. It uses
`reitit-core`for the routing and `sieppari` for interceptors.

Lets start with a complete example:

```clojure
(require '[vertx.http :as vh])
(require '[vertx.web :as vw])
(require '[vertx.web.interceptors :as vwi])

(defn hello-world-handler
  [req]
  {:status 200
   :body "Hello world!\n"})

(defn on-start
  [ctx]
  (let [routes [["/" {:interceptors [(vwi/cookies)]
                      :all hello-world-handler}]]
        handler (vw/handler ctx
                            (vw/assets "/static/*" {:root "resources/public/static"})
                            (vw/router routes))]
    (vh/server {:handler handler
                :port 2022})))

(->> (vc/verticle {:on-start on-start})
     (vc/deploy! system))
```

The routes are defined using `reitit-core` and the interceptors are
using `sieppari` as underlying implementation. The request object is
very similar to the one explained in `vertx.http`.

The main difference with `vertx.http` is that the handler is called
when the body is ready to be used and is available under `:body`
keyword on the request.

All additional features such that reading the query/form params,
parse/write cookies, cors and file uploads are provided with
interceptors as pluggable pieces:

- `vertx.web.interceptors/uploads` parses the vertx uploaded file data
  structure and expose it as clojure maps under `:uploads` key.
- `vertx.web.interceptors/params` parses the query string and form
  params in the body if the content-type is appropriate and exposes
  them under `:params`.
- `vertx.web.interceptors/cors` properly sets the CORS headers.
- `vertx.web.interceptors/cookies` handles the cookies reading from
  the request and cookies writing from the response.


## License ##

```
This Source Code Form is subject to the terms of the Mozilla Public
License, v. 2.0. If a copy of the MPL was not distributed with this
file, You can obtain one at http://mozilla.org/MPL/2.0/.
```

