;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.worker
  "Render worker entry point.

  Owns one render-wasm module and renders one export request at a time. The
  Skia calls are synchronous, so running them here is what lets several exports
  progress at once: the main thread keeps serving HTTP, zipping and uploading
  while this thread is blocked inside a render.

  Messages in:  {type: \"render\", params: <transit>, cancel: SharedArrayBuffer}
  Messages out: {type: \"ready\"}
                {type: \"object\", payload: <transit>}   one per rendered object
                {type: \"done\"} | {type: \"error\", message, code}"
  (:require
   ["node:worker_threads" :as wt]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.wasm.render :as render]
   [promesa.core :as p]))

(defn- post!
  [message]
  (.postMessage ^js wt/parentPort message))

(defn- cancelled-fn
  [buffer]
  (if (some? buffer)
    (let [signal (js/Int32Array. buffer)]
      (fn [] (pos? (js/Atomics.load signal 0))))
    (constantly false)))

(defn- handle-render
  [data]
  (let [params (-> (unchecked-get data "params")
                   (t/decode-str)
                   (assoc :cancelled? (cancelled-fn (unchecked-get data "cancel"))))]
    (->> (render/render params
                        (fn [object]
                          (post! #js {:type "object" :payload (t/encode-str object)})))
         (p/fmap (fn [_] (post! #js {:type "done"})))
         (p/merr (fn [cause]
                   (l/warn :hint "render worker: request failed" :cause cause)
                   (post! #js {:type "error"
                               :message (or (ex-message cause) (str cause))
                               :code (some-> cause ex-data :code name)})
                   (p/resolved nil))))))

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
  (post! #js {:type "ready"})
  (l/info :hint "render worker ready"))
