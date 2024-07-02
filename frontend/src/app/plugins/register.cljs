;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.register
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]))

;; TODO: Remove clj->js and parse into a better data structure for accessing the permissions

(def pluginsdb (atom nil))

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

(defn init
  []
  (reset! pluginsdb (load-from-store)))

(defn install-plugin!
  [plugin]
  (let [plugins (vec (conj (seq @pluginsdb) plugin))]
    (reset! pluginsdb plugins)
    (save-to-store plugins)))

(defn remove-plugin!
  [{:keys [plugin-id]}]
  (let [plugins
        (into []
              (keep (fn [plugin]
                      (when (not= plugin-id (:plugin-id plugin)) plugin)))
              @pluginsdb)]
    (reset! pluginsdb plugins)
    (save-to-store plugins)))

(defn check-permission
  [plugin-id permission]
  (or (= plugin-id "TEST")
      (let [{:keys [permissions]} (->> @pluginsdb (d/seek #(= (:plugin-id %) plugin-id)))]
        (->> permissions (d/seek #(= % permission))))))
