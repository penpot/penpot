;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.plugins
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.store :as st]
   [app.plugins.register :as pr]
   [app.util.globals :as ug]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn save-current-plugin
  [id]
  (ptk/reify ::save-current-plugin
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :open-plugins] (fnil conj #{}) id))))

(defn remove-current-plugin
  [id]
  (ptk/reify ::remove-current-plugin
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-local :open-plugins] (fnil disj #{}) id))))

(defn open-plugin!
  [{:keys [plugin-id name description host code icon permissions]}]
  (try
    (st/emit! (save-current-plugin plugin-id))
    (.ɵloadPlugin
     ^js ug/global
     #js {:pluginId plugin-id
          :name name
          :description description
          :host host
          :code code
          :icon icon
          :permissions (apply array permissions)}
     (fn []
       (st/emit! (remove-current-plugin plugin-id))))

    (catch :default e
      (st/emit! (remove-current-plugin plugin-id))
      (.error js/console "Error" e))))

(defn close-plugin!
  [{:keys [plugin-id]}]
  (try
    (.ɵunloadPlugin ^js ug/global plugin-id)
    (catch :default e
      (.error js/console "Error" e))))

(defn close-current-plugin
  []
  (ptk/reify ::close-current-plugin
    ptk/EffectEvent
    (effect [_ state _]
      (let [ids (dm/get-in state [:workspace-local :open-plugins])]
        (doseq [id ids]
          (close-plugin! (pr/get-plugin id)))))))

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
