;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.wasm.worker
  "Render worker entry point.

  Owns one render-wasm module and renders one export request at a time. The
  Skia calls are synchronous, so running them here is what lets several exports
  progress at once: the main thread keeps polling the queue and settling jobs
  while this thread is blocked inside a render. There are no promesa chains
  in this namespace.

  Messages in:  {type: \"render\", params: <transit>, cancel: SharedArrayBuffer}
  Messages out: {type: \"ready\"}
                {type: \"object\", payload: <transit>}   one per rendered object
                {type: \"done\"} | {type: \"error\", message, code}"
  (:require
   ["node:worker_threads" :as wt]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [exporter.wasm.render :as render]))

(def parent-port
  "The channel renders report through: the parent port of a render
  thread. A plain def so tests — which run on the main thread, where
  the port is null — rebind it, the way they rebind `fetch`."
  wt/parentPort)

(defn- post
  [message]
  (.postMessage ^js parent-port message))

(defn- cancelled-fn
  [buffer]
  (if (some? buffer)
    (let [signal (js/Int32Array. buffer)]
      (fn [] (pos? (js/Atomics.load signal 0))))
    (constantly false)))

(defn ^:async handle-render
  "One render message: split the render env off the params, run the
  pipeline, and post every object plus the settle. Never rejects: a
  failure posts `error`, so the main thread always settles the lease."
  [data]
  (try
    (let [{env ::render/env :as params} (t/decode-str (unchecked-get data "params"))]
      (when (nil? env)
        (throw (ex/error :type :assertion
                         :code :env-missing
                         :hint "worker render needs ::render/env in params")))
      (let [params (assoc (dissoc params ::render/env)
                          :cancelled? (cancelled-fn (unchecked-get data "cancel")))]
        (await (render/render env params
                              (fn [object]
                                (post #js {:type "object" :payload (t/encode-str object)}))))
        (post #js {:type "done"})))
    (catch :default cause
      (l/warn :hint "render worker: request failed" :cause cause)
      (post #js {:type "error"
                 :message (or (ex-message cause) (str cause))
                 :code (some-> cause ex-data :code name)}))))

(defn- on-message
  [data]
  (case (unchecked-get data "type")
    "render" (handle-render data)
    (l/warn :hint "render worker: unknown message" :type (unchecked-get data "type"))))

(defonce ^:private listening
  ;; `defonce` survives a hot reload, so a reload does not stack a second
  ;; listener on the port. The indirection through the var keeps the reloaded
  ;; `on-message` in play instead of pinning the one captured at boot.
  (delay
    (.on ^js wt/parentPort "message" (fn [data] (on-message data)))
    true))

(defn main
  [& _]
  @listening
  (post #js {:type "ready"})
  (l/info :hint "render worker ready"))
