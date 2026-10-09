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
  `:exporter.wasm/pool`) plus the static render config
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
  worker on wasm.

  The renderer is also a system service (`:exporter/renderer`): its
  config value carries the pools as refs plus the static config, and
  its running instance is the render fn with that config closed over,
  which is what the consumer renders through — the consumer never
  calls this namespace directly."
  (:require
   [app.common.schema :as sm]
   [exporter.browser.scope :as bscope]
   [exporter.utils.system :as system]
   [exporter.wasm.render :as wrender]
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

(defn- render-env
  "The render environment of one batch: internal and public endpoints,
  the management key, the temp area and the image cache budget. Read
  from the service config (wired from the environment); the worker
  thread never resolves any of it itself."
  [cfg]
  {:internal-uri     (:base-uri cfg)
   :public-uri       (:public-uri cfg)
   :management-key   (:management-key cfg)
   :tmpdir           (:exporter/tmpdir cfg)
   :image-cache-size (:image-cache-size cfg)})

(defn ^:async render
  "Renders every export, firing them all at once: the browser ones
  through their own scope, the wasm ones through one leased
  worker. Assumes a valid task — validation lives one level up, at
  the service entry, so through here a bad task never touches a pool
  because it never arrives. Rejects on the first failure."
  [cfg {:keys [exports on-object check-cancelled]}]
  (let [cfg      (assoc cfg
                        ::on-object on-object
                        ::check-cancelled check-cancelled)
        ;; Everything the worker thread needs beyond the export
        ;; itself rides in the posted params: the worker reads no
        ;; config and no globals.
        wexports (map #(assoc % ::wrender/env (render-env cfg))
                      (filter wasm? exports))
        bexports (remove wasm? exports)]
    (await (js/Promise.all
            (cond-> []
              (seq bexports)
              (conj (bscope/run cfg bexports))
              (seq wexports)
              (conj (scope/run cfg
                               (fn [render-fn]
                                 (js/Promise.all (map render-fn wexports))))))))
    nil))

(defn render-fn
  "The render entry of one running system: validates the task map and
  dispatches it through the facade with `cfg` closed over, resolving
  nil once every export landed. The single boundary where a bad batch
  is rejected before anything is leased — and the dependency the
  consumer renders through, so the consumer never calls the facade
  directly."
  [cfg]
  (fn [task]
    (render cfg (check-task task))))

(defmethod system/init-key :exporter/renderer
  [_ cfg]
  (render-fn cfg))
