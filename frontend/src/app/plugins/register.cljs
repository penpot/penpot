;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.register
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.types.plugins :as ctp]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]))

;; Stores the installed plugins information
(defonce ^:private registry (atom {}))

(defn plugins-list
  "Retrieves the plugin data as an ordered list of plugin elements"
  []
  (->> (:ids @registry)
       (mapv #(dm/get-in @registry [:data %]))))

(defn get-plugin
  [id]
  (dm/get-in @registry [:data id]))

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
          (conj "library:read")

          (contains? permissions "comment:write")
          (conj "comment:read"))

        origin (obj/get (js/URL. plugin-url) "origin")

        prev-plugin
        (->> (:data @registry)
             (vals)
             (d/seek (fn [plugin]
                       (and (= name (:name plugin))
                            (= origin (:host plugin))))))

        plugin-id (d/nilv (:plugin-id prev-plugin) (str (uuid/next)))

        manifest
        (d/without-nils
         {:plugin-id plugin-id
          :url plugin-url
          :name name
          :description desc
          :host origin
          :code code
          :icon icon
          :permissions (into #{} (map str) permissions)})]
    (when (sm/validate ::ctp/registry-entry manifest)
      manifest)))

(defn save-to-store
  []
  ;; TODO: need this for the transition to the new schema. We can remove eventually
  (let [registry (update @registry :data d/update-vals d/without-nils)]
    (->> (rp/cmd! :update-profile-props {:props {:plugins registry}})
         (rx/subs! identity))))

(defn load-from-store
  []
  (reset! registry (get-in @st/state [:profile :props :plugins] {})))

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
