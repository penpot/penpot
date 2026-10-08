;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer
  "Common renderer interface, validated with malli from day one.

  One entry point: `render` takes `sys` plus a task map
  (`:exports`, `:on-object`, `:check-cancelled`) and fires the whole
  batch at once, dispatching each export to the browser or the
  headless driver by its `:is-wasm` flag. A single export is a batch
  of one. The headless ones share one leased worker (serialized on
  it); the browser ones check out their own browser each (bounded by
  the pool). Rendered objects travel through `on-object`, so the
  batch itself resolves a vector of nils.

  `sys` is task-free infrastructure with process lifetime — the
  browser pool and render uris, the wasm pool instance, flags — what
  could one day come straight from the running system map. The task
  map holds everything per-task: the `exports` data plus the two
  injected indirections, `on-object` (the per-object collector) and
  `check-cancelled` (the zero-arg cancel check raising
  `:job-cancelled`). One closed schema validates the whole task, so a
  bad batch — or a non-fn injection — never touches a pool. Each
  driver takes the same `[sys params on-object check]` shape and
  implements cancel internally: cooperative checkpoints on the
  browser, checkpoints plus a mid-render abort of its own leased
  worker on wasm."
  (:require
   [app.common.schema :as sm]
   [exporter.renderer.browser :as browser]
   [exporter.renderer.wasm :as wasm]))

(def schema:type
  [:enum :png :jpeg :webp :pdf :svg])

(def schema:object
  [:map
   [:id :string]
   [:name :string]
   [:suffix :string]
   [:filename :string]
   [:share-id {:optional true} ::sm/uuid]])

(def schema:objects
  [:vector {:min 1} schema:object])

(def schema:render-params
  [:map
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:scale ::sm/number]
   [:token :string]
   [:type schema:type]
   [:objects schema:objects]
   [:is-wasm {:optional true} ::sm/boolean]
   [:job-id {:optional true} ::sm/uuid]])

(def schema:exports
  [:vector {:min 1} schema:render-params])

(def schema:task
  [:map {:closed true}
   [:exports schema:exports]
   [:on-object ::sm/fn]
   [:check-cancelled ::sm/fn]])

(def ^:private check-task
  (sm/check-fn schema:task :hint "invalid render task"))

(defn headless?
  "Whether `params` renders with render-wasm rather than a browser."
  [{:keys [is-wasm]}]
  (boolean is-wasm))

(defn ^:async render
  "Renders every export, firing them all at once. The whole task is
  validated before anything is leased, so a bad task never touches a
  pool. Rejects on the first failure."
  [sys & {:as task}]
  (let [{:keys [exports on-object check-cancelled] :as task}
        (check-task task)

        do-render
        (fn [wasm-render]
          (js/Promise.all
           (mapv (fn [params]
                   (if (and (headless? params) (fn? wasm-render))
                     (wasm-render params on-object)
                     (browser/render sys params on-object check-cancelled)))
                 exports)))]

    (if (some headless? exports)
      (await (wasm/with-scope sys (assoc task :on-scope do-render)))
      (await (do-render nil)))))
