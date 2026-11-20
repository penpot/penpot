;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.plugins
  "RPC for plugins runtime."
  (:require
   ["@penpot/plugins-runtime" :as runtime]
   [app.main.data.event :as ev]
   [app.main.errors :as errors]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.flex :as flex]
   [app.plugins.format :as format]
   [app.plugins.grid :as grid]
   [app.plugins.library :as library]
   [app.plugins.public-utils]
   [app.plugins.register :as preg]
   [app.plugins.ruler-guides :as rg]
   [app.plugins.shape :as shape]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- runtime-available?
  []
  (fn? (unchecked-get ug/global "ɵloadPlugin")))

(defn init-plugins-runtime
  []
  (runtime/initPluginsRuntime (fn [plugin-id] (api/create-context plugin-id)))
  ;; Signal even when the runtime failed to start: MCP initialization waits
  ;; for this promise and would otherwise hang forever.
  (preg/signal-runtime-ready)
  (runtime-available?))

(defn- runtime-unavailable-event
  []
  (ev/event {::ev/name "plugins-runtime-unavailable"
             :message (some-> (runtime/getPluginsRuntimeError) .-message)}))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (set! errors/is-plugin-error? runtime/isPluginError)

      (->> stream
           (rx/filter (ptk/type? ::features/initialize))
           (rx/observe-on :async)
           (rx/filter #(features/active-feature? @st/state "plugins/runtime"))
           (rx/take 1)
           (rx/map (fn [_]
                     (when-not (init-plugins-runtime)
                       (runtime-unavailable-event))))
           (rx/filter some?)))))

;; Prevent circular dependency
(set! flex/shape-proxy? shape/shape-proxy?)
(set! grid/shape-proxy? shape/shape-proxy?)
(set! format/shape-proxy shape/shape-proxy)
(set! rg/shape-proxy shape/shape-proxy)
(set! rg/shape-proxy? shape/shape-proxy?)

(set! shape/lib-typography-proxy? library/lib-typography-proxy?)
(set! shape/lib-component-proxy library/lib-component-proxy)
