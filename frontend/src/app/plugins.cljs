;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins
  "RPC for plugins runtime."
  (:require
   [app.common.uuid :as uuid]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.public-utils]
   [app.plugins.register :as register]
   [app.util.globals :refer [global]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def pluginsdb register/pluginsdb)
(def install-plugin! register/install-plugin!)
(def remove-plugin! register/remove-plugin!)

(defn init-plugins-runtime!
  []
  (when-let [init-runtime (obj/get global "initPluginsRuntime")]
    (register/init)
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

(defn parser-manifest
  [plugin-url ^js manifest]
  (let [name (obj/get manifest "name")
        desc (obj/get manifest "description")
        code (obj/get manifest "code")
        icon (obj/get manifest "icon")

        permissions (into #{} (obj/get manifest "permissions" []))
        permissions
        (cond-> permissions
          (contains? permissions "content:write")
          (conj "content:read")

          (contains? permissions "library:write")
          (conj "content:write"))

        origin (obj/get (js/URL. plugin-url) "origin")
        plugin-id (str (uuid/next))]
    {:plugin-id plugin-id
     :name name
     :description desc
     :host origin
     :code code
     :icon icon
     :permissions (->> permissions (mapv str))}))
