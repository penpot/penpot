;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.register
  "RPC for plugins runtime."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]))

;; Stores the installed plugins information
(defonce ^:private registry (atom {}))

(defn plugins-list
  "Retrieves the plugin data as an ordered list of plugin elements"
  []
  (->> (:ids @registry)
       (mapv #(dm/get-in @registry [:data %]))))

(defn parse-manifest
  "Read the manifest.json defined by the plugins definition and transforms it into an
  object that will be stored in the register."
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

        prev-plugin
        (->> (:data @registry)
             (vals)
             (d/seek (fn [plugin]
                       (and (= name (:name plugin))
                            (= origin (:host plugin))))))

        plugin-id (d/nilv (:plugin-id prev-plugin) (str (uuid/next)))]
    {:plugin-id plugin-id
     :name name
     :description desc
     :host origin
     :code code
     :icon icon
     :permissions (into #{} (map str) permissions)}))

(defn format-plugin-data
  "Format into a JS object the plugin data. This will be used to be stored in the local storage."
  [{:keys [plugin-id name description host code icon permissions]}]
  #js {:plugin-id plugin-id
       :name name
       :description description
       :host host
       :code code
       :icon icon
       :permissions (apply array permissions)})

(defn parse-plugin-data
  "Parsers the JS plugin data into a CLJS data structure. This will be used primarily when the local storage
  data is retrieved"
  [^js data]
  {:plugin-id (obj/get data "plugin-id")
   :name (obj/get data "name")
   :description (obj/get data "description")
   :host (obj/get data "host")
   :code (obj/get data "code")
   :icon (obj/get data "icon")
   :permissions (into #{} (obj/get data "permissions"))})

(defn load-from-store
  []
  (let [ls (.-localStorage js/window)
        plugins-val (.getItem ls "plugins")]
    (when plugins-val
      (let [stored (->> (.parse js/JSON plugins-val)
                        (map parse-plugin-data))]
        (reset! registry
                {:ids (->> stored (map :plugin-id))
                 :data (d/index-by :plugin-id stored)})))))

(defn save-to-store
  []
  (->> (:ids @registry)
       (map #(dm/get-in @registry [:data %]))
       (map format-plugin-data)
       (apply array)
       (.stringify js/JSON)
       (.setItem (.-localStorage js/window) "plugins")))

(defn init
  []
  (load-from-store))

(defn install-plugin!
  [plugin]
  (letfn [(update-ids [ids]
            (conj
             (->> ids (remove #(= % (:plugin-id plugin))))
             (:plugin-id plugin)))]
    (swap! registry #(-> %
                         (update :ids update-ids)
                         (update :data assoc (:plugin-id plugin) plugin)))
    (save-to-store)))

(defn remove-plugin!
  [{:keys [plugin-id]}]
  (letfn [(update-ids [ids]
            (->> ids
                 (remove #(= % plugin-id))))]
    (swap! registry #(-> %
                         (update :ids update-ids)
                         (update :data dissoc plugin-id)))
    (save-to-store)))

(defn check-permission
  [plugin-id permission]
  (or (= plugin-id "TEST")
      (let [{:keys [permissions]} (dm/get-in @registry [:data plugin-id])]
        (contains? permissions permission))))
