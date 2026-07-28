;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.worker
  "Entry point of the headless render worker thread.

  Owns one wasm module and runs `app.wasm.render` on it. Kept deliberately
  thin: everything here is message plumbing, so the pipeline itself stays
  free of worker concerns and the module never touches the main thread.

  Protocol (see `app.wasm.pool` for the other end). Params and objects cross
  the boundary transit-encoded, because they carry uuids and keywords that
  structured clone would mangle:

    in   {type: \"render\", id, params}
    out  {type: \"object\", id, object, path}   one per rendered object
    out  {type: \"done\",   id}
    out  {type: \"error\",  id, message}

  The pool sends one request at a time per worker, so there is no need to
  multiplex `id`s here — it is echoed back only so the pool can drop replies
  from a request it already gave up on."
  (:require
   ["node:worker_threads" :as wt]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.wasm.render :as render]
   [promesa.core :as p]))

(enable-console-print!)
(l/setup! {:app :info})

(defn- post!
  [msg]
  (.postMessage ^js wt/parentPort msg))

(defn- handle-render!
  [id params]
  (->> (render/render (t/decode-str params)
                      (fn [object]
                        (post! #js {:type   "object"
                                    :id     id
                                    :object (t/encode-str (dissoc object :path))
                                    :path   (:path object)})))
       (p/fnly (fn [_ cause]
                 (if cause
                   (post! #js {:type    "error"
                               :id      id
                               :message (or (ex-message cause) (str cause))})
                   (post! #js {:type "done" :id id}))))))

(defn main
  [& _]
  (.on ^js wt/parentPort "message"
       (fn [^js msg]
         (when (= "render" (unchecked-get msg "type"))
           (handle-render! (unchecked-get msg "id")
                           (unchecked-get msg "params"))))))
