;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.icons
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [type (or type :builtin)
          id (or id (if (= type :builtin) 1 nil))
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :icons] data)
          (assoc-in [:dashboard :section] :dashboard/icons)))))

  ;; rs/EffectEvent
  ;; (-apply-effect [_ state]
  ;;   (when (nil? (:icon-colls-by-id state))
  ;;     (reset! st/loader true)))

  ;; rs/WatchEvent
  ;; (-apply-watch [_ state s]
  ;;   (if (nil? (:icon-colls-by-id state))
  ;;     (rx/merge
  ;;      (rx/of (fetch-collections))
  ;;        (->> (rx/filter collections-fetched? s)
  ;;             (rx/take 1)
  ;;             (rx/do #(reset! st/loader false))
  ;;             (rx/ignore)))
  ;;     (rx/empty))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (r/navigate :dashboard/icons
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

;; --- Color Collections Fetched

;; (defrecord CollectionsFetched [items]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (reduce (fn [state item]
;;               (let [id (:id item)
;;                     item (assoc item :type :own)]
;;                 (assoc-in state [:icon-colls-by-id id] item)))
;;             state
;;             items)))

;; (defn collections-fetched
;;   [items]
;;   (CollectionsFetched. items))

;; --- Fetch Color Collections

;; (defrecord FetchCollections []
;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (->> (rp/req :fetch/icon-collections)
;;          (rx/map :payload)
;;          (rx/map collections-fetched))))

;; (defn fetch-collections
;;   []
;;   (FetchCollections.))

;; --- Collection Created

;; (defrecord CollectionCreated [item]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (let [item (assoc item :type :own)]
;;       (-> state
;;           (assoc-in [:icon-colls-by-id (:id item)] item)
;;           (assoc-in [:dashboard :collection-id] (:id item))
;;           (assoc-in [:dashboard :collection-type] :own)))))

;; (defn collection-created
;;   [item]
;;   (CollectionCreated. item))

;; --- Create Collection

;; (defrecord CreateCollection []
;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (let [coll {:name "Unnamed collection"
;;                 :id (uuid/random)}]
;;       (->> (rp/req :create/icon-collection coll)
;;            (rx/map :payload)
;;            (rx/map collection-created)))))

;; (defn create-collection
;;   []
;;   (CreateCollection.))

;; (defn collections-fetched?
;;   [v]
;;   (instance? CollectionsFetched v))

;; --- Collection Updated

;; (defrecord CollectionUpdated [item]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (update-in state [:icon-colls-by-id (:id item)]  merge item)))

;; (defn collection-updated
;;   [item]
;;   (CollectionUpdated. item))

;; --- Update Collection

;; (defrecord UpdateCollection [id]
;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (let [item (get-in state [:icon-colls-by-id id])]
;;       (->> (rp/req :update/icon-collection item)
;;            (rx/map :payload)
;;            (rx/map collection-updated)))))

;; (defn update-collection
;;   [id]
;;   (UpdateCollection. id))

;; --- Rename Collection

;; (defrecord RenameCollection [id name]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (assoc-in state [:icon-colls-by-id id :name] name))

;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (rx/of (update-collection id))))

;; (defn rename-collection
;;   [item name]
;;   (RenameCollection. item name))

;; --- Delete Collection

;; (defrecord DeleteCollection [id]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (update state :icon-colls-by-id dissoc id))

;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (->> (rp/req :delete/icon-collection id)
;;          (rx/ignore))))

;; (defn delete-collection
;;   [id]
;;   (DeleteCollection. id))

;; --- Icon Created

;; (defrecord IconCreated [item]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (update-in state [:icon-colls-by-id (:collection item) :icons]
;;                #(conj % item))))

;; (defn icon-created
;;   [item]
;;   (IconCreated. item))

;; --- Create Icon

;; (defrecord CreateIcons [coll-id files]
;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (let [files-to-array (fn [js-col]
;;                            (-> (clj->js [])
;;                                (.-slice)
;;                                (.call js-col)
;;                                (js->clj)))
;;           icons-data (map (fn [file] {:coll coll-id
;;                                        :id (uuid/random)
;;                                        :file file}) (files-to-array files))]
;;       (->> (rx/from-coll icons-data)
;;            (rx/flat-map #(rp/req :create/icon %))
;;            (rx/map :payload)
;;            (rx/map icon-created)))))

;; (defn create-icons
;;   [coll-id files]
;;   (CreateIcons. coll-id files))

;; --- Icons Fetched

;; (defrecord IconsFetched [coll-id items]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (assoc-in state [:icon-colls-by-id coll-id :icons] (set items))))

;; (defn icons-fetched
;;   [coll-id items]
;;   (IconsFetched. coll-id items))

;; --- Load Icons

;; (defrecord FetchIcons [coll-id]
;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (let [params {:coll coll-id}]
;;       (->> (rp/req :fetch/icons params)
;;            (rx/map :payload)
;;            (rx/map #(icons-fetched coll-id %))))))

;; (defn fetch-icons
;;   [coll-id]
;;   (FetchIcons. coll-id))

;; --- Delete Icons

;; (defrecord DeleteIcon [coll-id icon]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     (update-in state [:icon-colls-by-id coll-id :icons] disj icon))

;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (->> (rp/req :delete/icon (:id icon))
;;          (rx/ignore))))

;; (defn delete-icon
;;   [coll-id icon]
;;   (DeleteIcon. coll-id icon))

;; --- Remove Icon

;; (defrecord RemoveIcons [id icons]
;;   rs/UpdateEvent
;;   (-apply-update [_ state]
;;     #_(update-in state [:icon-colls-by-id id :data]
;;                #(set/difference % icons)))

;;   rs/WatchEvent
;;   (-apply-watch [_ state s]
;;     (rx/of (update-collection id))))

;; (defn remove-icons
;;   "Remove icon in a collection."
;;   [id icons]
;;   (RemoveIcons. id icons))


;; --- Select icon

(defrecord SelectIcon [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :selected] conj id)))

(defrecord DeselectIcon [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :selected] disj id)))

(defrecord ToggleIconSelection [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :selected])]
      (rx/of
       (if (selected id)
         (DeselectIcon. id)
         (SelectIcon. id))))))

(defn toggle-icon-selection
  [id]
  (ToggleIconSelection. id))

;; --- Delete Selected

;; (defrecord DeleteSelected []
;;   rs/WatchEvent
;;   (-apply-watch [_ state stream]
;;     (let [{:keys [id selected]} (get state :dashboard)]
;;       (rx/of (remove-icons id selected)
;;              #(assoc-in % [:dashboard :selected] #{})))))

;; (defn delete-selected
;;   []
;;   (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :icons] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))
