;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins
  "RPC for plugins runtime."
  (:require
   [app.main.features :as features]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.flex :as flex]
   [app.plugins.format :as format]
   [app.plugins.grid :as grid]
   [app.plugins.library :as library]
   [app.plugins.public-utils]
   [app.plugins.ruler-guides :as rg]
   [app.plugins.shape :as shape]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn init-plugins-runtime!
  []
  (when-let [init-runtime (obj/get global "initPluginsRuntime")]
    (init-runtime (fn [plugin-id] (api/create-context plugin-id)))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::features/initialize))
           (rx/observe-on :async)
           (rx/filter #(features/active-feature? @st/state "plugins/runtime"))
           (rx/take 1)
           (rx/tap init-plugins-runtime!)
           (rx/ignore)))))

;; Prevent circular dependency
(set! flex/shape-proxy? shape/shape-proxy?)
(set! grid/shape-proxy? shape/shape-proxy?)
(set! format/shape-proxy shape/shape-proxy)
(set! rg/shape-proxy shape/shape-proxy)
(set! rg/shape-proxy? shape/shape-proxy?)

(set! shape/lib-typography-proxy? library/lib-typography-proxy?)
(set! shape/lib-component-proxy library/lib-component-proxy)
