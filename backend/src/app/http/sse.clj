;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.http.sse
  "SSE (server sent events) helpers"
  (:refer-clojure :exclude [tap])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.http.errors :as errors]
   [promesa.core :as p]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [promesa.util :as pu]
   [ring.response :as rres])
  (:import
   java.io.OutputStream))

(def ^:dynamic *channel* nil)

(defn- write!
  [^OutputStream output ^bytes  data]
  (l/trc :hint "writting data" :data data :length (alength data))
  (.write output data)
  (.flush output))

(defn- create-writer-loop
  [^OutputStream output]
  (try
    (loop []
      (when-let [event (sp/take! *channel*)]
        (let [result (ex/try! (write! output event))]
          (if (ex/exception? result)
            (l/wrn :hint "unexpected exception on sse writer" :cause result)
            (recur)))))
    (finally
      (pu/close! output))))

(defn- encode
  [[name data]]
  (try
    (let [data (with-out-str
                 (println "event:" (d/name name))
                 (println "data:" (t/encode-str data {:type :json-verbose}))
                 (println))]
      (.getBytes data "UTF-8"))
    (catch Throwable cause
      (l/err :hint "unexpected error on encoding value on sse stream"
             :cause cause)
      nil)))

;; ---- PUBLIC API

(def default-headers
  {"Content-Type" "text/event-stream;charset=UTF-8"
   "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
   "Pragma" "no-cache"})

(defn tap
  ([data] (tap "event" data))
  ([name data]
   (when-let [channel *channel*]
     (sp/put! channel [name data])
     nil)))

(defn response
  [handler & {:keys [buf] :or {buf 32} :as opts}]
  (fn [request]
    {::rres/headers default-headers
     ::rres/status 200
     ::rres/body (reify rres/StreamableResponseBody
                   (-write-body-to-stream [_ _ output]
                     (binding [*channel* (sp/chan :buf buf :xf (keep encode))]
                       (let [writer (px/run! :virtual (partial create-writer-loop output))]
                         (try
                           (tap "end" (handler))
                           (catch Throwable cause
                             (tap "error" (errors/handle' cause request)))
                           (finally
                             (sp/close! *channel*)
                             (p/await! writer)))))))}))
