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
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn init!
  []
  (->> st/stream
       (rx/filter (ptk/type? ::features/initialize))
       (rx/take 1)
       ;; We need to wait to the init event to finish
       (rx/observe-on :async)
       (rx/subs!
        (fn []
          (when (features/active-feature? @st/state "plugins/runtime")
            (when-let [init-runtime (obj/get global "initPluginsRuntime")]
              (let [context (api/create-context)]
                (init-runtime context))))))))
