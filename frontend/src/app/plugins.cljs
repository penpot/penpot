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

(defn parser-manifest
  [plugin-url ^js manifest]
  (let [name (obj/get manifest "name")
        desc (obj/get manifest "description")
        code (obj/get manifest "code")
        icon (obj/get manifest "icon")
        permissions (obj/get manifest "permissions")
        origin (obj/get (js/URL. plugin-url) "origin")
        plugin-id (str (uuid/next))]
    {:plugin-id plugin-id
     :name name
     :description desc
     :host origin
     :code code
     :icon icon
     :permissions (->> permissions (mapv str))}))

(defn load-from-store
  []
  (let [ls (.-localStorage js/window)
        plugins-val (.getItem ls "plugins")]
    (when plugins-val
      (let [plugins-js (.parse js/JSON plugins-val)]
        (js->clj plugins-js {:keywordize-keys true})))))

(defn save-to-store
  [plugins]
  (let [ls (.-localStorage js/window)
        plugins-js (clj->js plugins)
        plugins-val (.stringify js/JSON plugins-js)]
    (.setItem ls "plugins" plugins-val)))
