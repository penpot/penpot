;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.runner.server
  "An owned loopback HTTP server for one immutable renderer build."
  (:require
   ["fs" :as fs]
   ["http" :as http]
   ["path" :as path]
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.node.settings :as settings]
   [clojure.string :as str]))

(def ^:private mime
  {".html" "text/html; charset=utf-8"
   ".js" "application/javascript"
   ".cjs" "application/javascript"
   ".mjs" "application/javascript"
   ".wasm" "application/wasm"
   ".css" "text/css; charset=utf-8"
   ".json" "application/json"
   ".svg" "image/svg+xml"
   ".png" "image/png"})

(def ^:private status-text
  {400 "bad request"
   403 "forbidden"
   404 "not found"
   405 "method not allowed"})

(def ^:private fs-error-status
  {"ENOENT" 404
   "ENOTDIR" 404
   "EACCES" 403})

(def ^:private owners
  "Maps each owned server to {:sockets Set :shutdown atom}."
  (js/WeakMap.))

;; Request URL to file

(defn- rejection
  "Returns an Error that `error-status` maps to `status`."
  [status message]
  (ex-info message {:status status}))

(defn- unsafe-pathname?
  [pathname]
  (or (str/includes? pathname "\u0000")
      (str/includes? pathname "\\")
      (some #{".."} (str/split pathname #"/"))))

(defn- parse-pathname
  "Returns the decoded path of a request URL without its query and fragment.

  Throws:
    400 for a path without a leading slash and for a malformed escape.
    403 for a path with a NUL, a backslash or a `..` segment."
  [url]
  (let [pathname (js/decodeURIComponent (first (str/split url #"[?#]" 2)))]
    (cond
      (not (str/starts-with? pathname "/")) (throw (rejection 400 "invalid request path"))
      (unsafe-pathname? pathname)           (throw (rejection 403 "forbidden request path"))
      :else                                 pathname)))

(defn- pathname->relative
  [pathname]
  (if (= pathname "/") "index.html" (subs pathname 1)))

(defn- contained
  "Returns `file` when it lies within `root`, otherwise throws 403 with `message`."
  [root message file]
  (if (files/contained? root file)
    file
    (throw (rejection 403 message))))

(defn- request-file
  "Returns the real path of the file that `url` selects below `root`.

  Throws:
    400 or 403 for a bad path.
    403 for a path or a symlink that leaves `root`.
    The filesystem error when the file cannot be resolved."
  [root url]
  (->> (parse-pathname url)
       (pathname->relative)
       (path/resolve root)
       (contained root "forbidden request path")
       (fs/realpathSync)
       (contained root "forbidden artifact symlink")))

;; File to response

(defn- error-status
  "Returns the HTTP status for a failure while serving a request."
  [error]
  (or (:status (ex-data error))
      (when (instance? js/URIError error) 400)
      (get fs-error-status (.-code ^js error) 500)))

(defn- error-response
  [method status]
  {:status status
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body (when-not (= "HEAD" method)
           (get status-text status "server error"))})

(defn- method-not-allowed
  [method]
  (assoc-in (error-response method 405) [:headers "Allow"] "GET, HEAD"))

(defn- file-response
  [method file ^js stat]
  {:status 200
   :headers {"Content-Type" (get mime (path/extname file) "application/octet-stream")
             "Content-Length" (.-size stat)}
   :body (when (= "GET" method)
           (fs/readFileSync file))})

(defn- get-file-response
  [root method url]
  (try
    (let [file     (request-file root url)
          ^js stat (fs/statSync file)]
      (if (.isFile stat)
        (file-response method file stat)
        (error-response method 404)))
    (catch :default error
      (error-response method (error-status error)))))

(defn- respond
  "Returns {:status :headers :body} for a request with `method` and `url`."
  [root method url]
  (if (contains? #{"GET" "HEAD"} method)
    (get-file-response root method url)
    (method-not-allowed method)))

(defn- write-response
  [^js response {:keys [status headers body]}]
  (.writeHead response status (clj->js headers))
  (.end response body))

(defn- serve
  [root ^js request ^js response]
  (write-response response (respond root (.-method request) (.-url request))))

;; Shutdown

(defn- close-failure
  "Returns `error` unless it is nil or reports a server that was not running."
  [error]
  (when-not (or (nil? error)
                (= "ERR_SERVER_NOT_RUNNING" (.-code ^js error)))
    error))

(defn- close-server
  "Closes `server` and destroys its `sockets`.

  Returns a promise that resolves once the server closed. A server that is
  not running counts as closed."
  [^js server ^js sockets]
  (js/Promise.
   (fn [resolve reject]
     (let [settle (fn [error]
                    (if-let [failure (close-failure error)]
                      (reject failure)
                      (resolve nil)))]
       (try
         (.close server settle)
         (run! #(.destroy ^js %) (array-seq (js/Array.from sockets)))
         (catch :default error
           (settle error)))))))

(defn close
  "Closes an owned host once and destroys only that server's connections.

  Args:
    host: Map returned by `open`.

  Returns:
    Promise that resolves once the server closed. Repeated calls return the
    same promise, including during startup."
  [{:keys [server]}]
  (let [{:keys [sockets shutdown]} (.get owners server)]
    (or @shutdown
        (reset! shutdown (close-server server sockets)))))

;; Startup

(defn- track-connection
  [^js sockets shutdown ^js socket]
  (.add sockets socket)
  (.once socket "close" #(.delete sockets socket))
  (when @shutdown
    (.destroy socket)))

(defn- create-host
  "Returns {:server :build} for a server of `build`'s files that does not
  listen yet."
  [build]
  (let [root       (fs/realpathSync (:dir build))
        ^js server (http/createServer (partial serve root))
        sockets    (js/Set.)
        shutdown   (atom nil)]
    (.set owners server {:sockets sockets :shutdown shutdown})
    (.on server "connection" (partial track-connection sockets shutdown))
    {:server server :build build}))

(defn- aborted?
  [^js signal]
  (and signal (.-aborted signal)))

(defn- aborted-error
  []
  (ex-info "renderer server startup aborted" {:type ::aborted}))

(defn- timeout-error
  [timeout-ms]
  (ex-info "renderer server startup timed out"
           {:type ::startup-timeout :timeout-ms timeout-ms}))

(defn- subscribe-startup
  "Routes the end of `server` startup to `succeed` or `fail`.

  `succeed` receives the listening event. `fail` receives the first error
  event of `server`, an `::aborted` error when `signal` (optional) aborts and
  a `::startup-timeout` error after `timeout-ms`.

  Returns a function that removes the subscriptions."
  [^js server signal timeout-ms succeed fail]
  (let [on-abort #(fail (aborted-error))
        timer    (js/setTimeout #(fail (timeout-error timeout-ms)) timeout-ms)]
    (.once server "listening" succeed)
    (.once server "error" fail)
    (when signal
      (.addEventListener ^js signal "abort" on-abort #js {:once true}))
    (fn []
      (js/clearTimeout timer)
      (.removeListener server "listening" succeed)
      (.removeListener server "error" fail)
      (when signal
        (.removeEventListener ^js signal "abort" on-abort)))))

(defn- ^:async listen
  "Waits until `server` listens on 127.0.0.1 and an ephemeral port.

  Aborting `close-signal` closes the server. Rejects as `subscribe-startup`
  describes, and when `signal` is already aborted, without listening."
  [^js server close-signal signal timeout-ms]
  (when (aborted? signal)
    (throw (aborted-error)))
  ;; Nothing awaits before the subscription, so an abort that follows the
  ;; call reaches it.
  (let [^js startup (js/Promise.withResolvers)
        release     (subscribe-startup server signal timeout-ms
                                       (.-resolve startup) (.-reject startup))]
    (try
      (.listen server #js {:host "127.0.0.1" :port 0 :signal close-signal})
      (await (.-promise startup))
      (finally
        (release)))))

(defn- ^:async startup-failure
  "Closes `host` after a failed startup.

  Returns `error`, or an Error that also holds the failure of the close."
  [host ^js controller error]
  (.abort controller)
  (try
    (await (close host))
    error
    (catch :default close-error
      (errors/combine
       [{:phase "server-startup" :operation :listen :error error}
        {:phase "cleanup" :operation :close-server :error close-error}]))))

(defn- base-url
  [^js server]
  (str "http://127.0.0.1:" (.-port (.address server))))

(defn ^:async open
  "Starts serving build's directory on 127.0.0.1 and an ephemeral port.

  Args:
    build: Map with the directory to serve at `:dir`.
    options: Optional map with `:startup-timeout-ms` (30000 by default) and
      `:signal`, an AbortSignal.

  Returns:
    Promise of {:server NodeHttpServer :base-url string :build build}.

  Throws:
    Rejects on abort, timeout and listen errors, after closing the owned
    server. A failed close joins the startup error."
  ([build] (open build {}))
  ([build {:keys [startup-timeout-ms signal]
           :or {startup-timeout-ms settings/default-startup-timeout-ms}}]
   (let [host       (create-host build)
         controller (js/AbortController.)]
     (try
       (await (listen (:server host) (.-signal controller) signal startup-timeout-ms))
       (assoc host :base-url (base-url (:server host)))
       (catch :default error
         (let [failure (await (startup-failure host controller error))]
           (throw failure)))))))
