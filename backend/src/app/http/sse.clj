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
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.http.errors :as errors]
   [app.util.events :as events]
   [promesa.exec :as px]
   [promesa.exec.csp :as sp]
   [promesa.util :as pu]
   [yetti.response :as yres])
  (:import
   java.io.OutputStream))

(defn- write!
  [^OutputStream output ^bytes data]
  (l/trc :hint "writting data" :data data :length (alength data))
  (.write output data)
  (.flush output))

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

(defn response
  [handler & {:keys [buf] :or {buf 32} :as opts}]
  (fn [request]
    {::yres/headers default-headers
     ::yres/status 200
     ::yres/body (yres/stream-body
                  (fn [_ output]
                    (let [channel  (sp/chan :buf buf :xf (keep encode))
                          listener (events/start-listener
                                    channel
                                    (partial write! output)
                                    (partial pu/close! output))]
                      (try
                        (binding [events/*channel* channel]
                          (let [result (handler)]
                            (events/tap :end result)))

                        (catch Throwable cause
                          (let [result (errors/handle' cause request)]
                            (events/tap channel :error result)))

                        (finally
                          (sp/close! channel)
                          (px/await! listener))))))}))
