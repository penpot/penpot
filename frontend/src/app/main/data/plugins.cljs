;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.plugins
  (:require
   [app.plugins.register :as pr]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn open-plugin!
  [{:keys [plugin-id name description host code icon permissions]}]
  (try
    (.ɵloadPlugin
     ^js ug/global
     #js {:pluginId plugin-id
          :name name
          :description description
          :host host
          :code code
          :icon icon
          :permissions (apply array permissions)})
    (catch :default e
      (.error js/console "Error" e))))

(defn delay-open-plugin
  [plugin]
  (ptk/reify ::delay-open-plugin
    ptk/UpdateEvent
    (update [_ state]
      (assoc state ::open-plugin (:plugin-id plugin)))))

(defn check-open-plugin
  []
  (ptk/reify ::check-open-plugin
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [pid (::open-plugin state)]
        (open-plugin! (pr/get-plugin pid))
        (rx/of #(dissoc % ::open-plugin))))))
