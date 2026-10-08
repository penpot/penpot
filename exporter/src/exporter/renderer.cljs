;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer
  "Common renderer interface, validated with malli from day one.

  One entry point: `render` takes `cfg` plus a task map
  (`:exports`, `:on-object`, `:check-cancelled`), validates it, and
  fires the whole batch at once — the browser exports through
  `exporter.browser.scope`, the wasm ones through one leased
  worker in `exporter.wasm.scope`. A single export is a batch of one.
  Rendered objects travel through `on-object`; the batch itself
  resolves nil once every export landed.

  `cfg` is task-free infrastructure with process lifetime: the pool
  services under their system keys (`:exporter.browser/pool`,
  `:exporter.wasm.pool/pool`) plus the static render config
  (`:base-uri`, `:public-uri`, `:svgo?`) — a view over the running
  system map that each domain reads with its own keys. The task
  map holds everything per-task: the `exports` data plus the two
  injected indirections, `on-object` (the per-object collector) and
  `check-cancelled` (the zero-arg check-cancelled raising
  `:job-cancelled`). One closed schema validates the whole task, so a
  bad batch — or a non-fn injection — never touches a pool. Each
  driver takes the same `[cfg params on-object check-cancelled]` shape and
  implements cancel internally: cooperative checkpoints on the
  browser, checkpoints plus a mid-render abort of its own leased
  worker on wasm."
  (:require
   [app.common.schema :as sm]
   [exporter.browser.scope :as bscope]
   [exporter.wasm.scope :as scope]))

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

(defn wasm?
  "Whether `params` renders with render-wasm rather than a browser."
  [{:keys [is-wasm]}]
  (boolean is-wasm))

(defn ^:async render
  "Renders every export, firing them all at once: the browser ones
  through their own scope, the wasm ones through one leased
  worker. The whole task is validated before anything is leased, so a
  bad task never touches a pool. Rejects on the first failure."
  [cfg & {:as task}]
  (let [{:keys [exports on-object check-cancelled]} (check-task task)
        cfg           (assoc cfg
                             ::on-object on-object
                             ::check-cancelled check-cancelled)
        wasm-exports    (filterv wasm? exports)
        browser-exports (remove wasm? exports)]
    (await (js/Promise.all
            (cond-> []
              (seq browser-exports)
              (conj (bscope/run cfg browser-exports))
              (seq wasm-exports)
              (conj (scope/run cfg
                               (fn [render-fn]
                                 (js/Promise.all (mapv render-fn wasm-exports))))))))
    nil))
