;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns benches.render-wasm.node.server
  "An owned loopback HTTP server for one immutable renderer build."
  (:require
   ["fs" :as fs]
   ["http" :as http]
   ["path" :as path]
   [benches.render-wasm.node.files :as files]
   [benches.render-wasm.node.options :as options]
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

(def ^:private owners (js/WeakMap.))

(defn- request-file
  [root url]
  (let [pathname (js/decodeURIComponent (first (str/split url #"[?#]" 2)))]
    (when-not (str/starts-with? pathname "/")
      (throw (ex-info "invalid request path" {:status 400})))
    (when (or (str/includes? pathname "\u0000")
              (str/includes? pathname "\\")
              (some #{".."} (str/split pathname #"/")))
      (throw (ex-info "forbidden request path" {:status 403})))
    (let [relative (if (= pathname "/") "index.html" (subs pathname 1))
          file     (path/resolve root relative)]
      (when-not (files/contained? root file)
        (throw (ex-info "forbidden request path" {:status 403})))
      (let [resolved (fs/realpathSync file)]
        (when-not (files/contained? root resolved)
          (throw (ex-info "forbidden artifact symlink" {:status 403})))
        resolved))))

(defn- respond-error
  [^js request ^js response status]
  (.writeHead response status #js {"Content-Type" "text/plain; charset=utf-8"})
  (.end response (when-not (= "HEAD" (.-method request))
                   (case status
                     400 "bad request"
                     403 "forbidden"
                     404 "not found"
                     405 "method not allowed"
                     "server error"))))

(defn- serve
  [root ^js request ^js response]
  (if-not (contains? #{"GET" "HEAD"} (.-method request))
    (do
      (.setHeader response "Allow" "GET, HEAD")
      (respond-error request response 405))
    (try
      (let [file     (request-file root (.-url request))
            ^js stat (fs/statSync file)]
        (if-not (.isFile stat)
          (respond-error request response 404)
          (let [body (when (= "GET" (.-method request)) (fs/readFileSync file))]
            (.writeHead response 200
                        #js {"Content-Type" (get mime (path/extname file) "application/octet-stream")
                             "Content-Length" (.-size stat)})
            (.end response body))))
      (catch :default error
        (respond-error request response
                       (or (:status (ex-data error))
                           (when (instance? js/URIError error) 400)
                           (case (.-code error)
                             "ENOENT" 404
                             "ENOTDIR" 404
                             "EACCES" 403
                             500)))))))

(defn close
  "Closes an owned host once and destroys only that server's connections.

  Repeated calls return the same shutdown promise, including during startup."
  [{:keys [server]}]
  (let [{:keys [sockets shutdown]} (.get owners server)]
    (or @shutdown
        (let [result (js/Promise.
                      (fn [resolve reject]
                        (try
                          (.close ^js server
                                  (fn [error]
                                    (if (and error (not= "ERR_SERVER_NOT_RUNNING" (.-code error)))
                                      (reject error)
                                      (resolve nil))))
                          (doseq [socket (array-seq (js/Array.from sockets))]
                            (.destroy ^js socket))
                          (catch :default error
                            (if (= "ERR_SERVER_NOT_RUNNING" (.-code error))
                              (resolve nil)
                              (reject error))))))]
          (reset! shutdown result)
          result))))

(defn open
  "Starts serving build's directory on 127.0.0.1 and an ephemeral port.

  Resolves to {:server NodeHttpServer :base-url string :build build}. Startup
  has a 30000 ms default timeout and accepts an optional AbortSignal. Abort,
  timeout and listen errors close the owned server before rejecting."
  ([build] (open build {}))
  ([build {:keys [startup-timeout-ms signal] :or {startup-timeout-ms options/default-startup-timeout-ms}}]
   (js/Promise.
    (fn [resolve reject]
      (let [root       (fs/realpathSync (:dir build))
            ^js server (http/createServer #(serve root %1 %2))
            sockets    (js/Set.)
            shutdown   (atom nil)
            settled    (atom false)
            controller (js/AbortController.)
            timer      (atom nil)
            host       {:server server :build build}]
        (.set owners server {:sockets sockets :shutdown shutdown})
        (.on server "connection"
             (fn [socket]
               (.add sockets socket)
               (.once ^js socket "close" #(.delete sockets socket))
               (when @shutdown
                 (.destroy ^js socket))))
        (letfn [(cleanup []
                  (js/clearTimeout @timer)
                  (.removeListener server "error" failed)
                  (.removeListener server "listening" listening)
                  (when signal
                    (.removeEventListener ^js signal "abort" aborted)))
                (failed [error]
                  (when (compare-and-set! settled false true)
                    (cleanup)
                    (.abort controller)
                    (.then (close host)
                           (fn [_] (reject error))
                           (fn [_] (reject error)))))
                (aborted []
                  (failed (ex-info "renderer server startup aborted" {:type ::aborted})))
                (listening []
                  (when (compare-and-set! settled false true)
                    (cleanup)
                    (resolve (assoc host :base-url
                                    (str "http://127.0.0.1:" (.-port (.address server)))))))]
          (.once server "error" failed)
          (.once server "listening" listening)
          (when signal
            (.addEventListener ^js signal "abort" aborted #js {:once true}))
          (if (and signal (.-aborted ^js signal))
            (aborted)
            (do
              (reset! timer
                      (js/setTimeout
                       #(failed (ex-info "renderer server startup timed out"
                                         {:type ::startup-timeout
                                          :timeout-ms startup-timeout-ms}))
                       startup-timeout-ms))
              (try
                (.listen server #js {:host "127.0.0.1"
                                     :port 0
                                     :signal (.-signal controller)})
                (catch :default error
                  (failed error)))))))))))
