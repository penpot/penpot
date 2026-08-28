;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.http.sse
  "SSE (server sent events) helpers"
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.http.content-negotiation :as cnegot]
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
  (l/trc :hint "writing data" :data data :length (alength data))
  (.write output data)
  (.flush output))

(defn- encode
  [encode-fn [name data]]
  (try
    (let [data (with-out-str
                 (println "event:" (d/name name))
                 (println "data:" (encode-fn data))
                 (println))]
      (.getBytes ^String data "UTF-8"))
    (catch Throwable cause
      (l/err :hint "unexpected error on encoding value on sse stream"
             :cause cause)
      nil)))

(defn- resolve-encoder
  [format]
  (case format
    :transit #(t/encode-str % {:type :json-verbose})
    :json    cnegot/json-encode-str))

;; ---- PUBLIC API

(def default-headers
  {"Content-Type" "text/event-stream;charset=UTF-8"
   "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
   "Pragma" "no-cache"
   "X-Accel-Buffering" "no"})

(defn response
  "Create a streaming SSE response. The payload of each event is
  encoded in transit or plain JSON depending on the request content
  negotiation (see `app.http.content-negotiation/negotiate-format`),
  transit being the default."
  [handler & {:keys [buf] :or {buf 32} :as opts}]
  (fn [request]
    (let [encode-fn (resolve-encoder (cnegot/negotiate-format request))]
      {::yres/headers default-headers
       ::yres/status 200
       ::yres/body (yres/stream-body
                    (fn [_ output]

                      (let [channel  (sp/chan :buf buf :xf (keep (partial encode encode-fn)))
                            listener (events/spawn-listener
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
                            (px/await! listener))))))})))
