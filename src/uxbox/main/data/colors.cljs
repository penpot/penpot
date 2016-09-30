;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.colors
  (:require [clojure.set :as set]
            [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.util.color :as color]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]))

;; --- Helpers

(defn- assoc-collection
  [state coll]
  (let [id (:id coll)
        coll (assoc coll :type :own)]
    (assoc-in state [:color-colls-by-id id] coll)))

(defn- dissoc-collection
  "A reduce function for dissoc the color collection
  to the state map."
  [state id]
  (update state :color-colls-by-id dissoc id))

;; --- Initialize

(declare fetch-collections)
(declare collections-fetched?)
(declare conditional-fetch)

(defrecord Initialize [type id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [type (or type :builtin)
          id (or id (if (= type :builtin) 1 nil))
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :colors] data)
          (assoc-in [:dashboard :section] :dashboard/colors))))

  ;; rs/EffectEvent
  ;; (-apply-effect [_ state]
  ;;   (when (nil? (:color-colls-by-id state))
  ;;     (reset! st/loader true)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (conditional-fetch))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Conditional fetch of Color Collections

(defrecord ConditionalFetch []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (if (nil? (:color-colls-by-id state))
      (rx/merge
       (rx/of (fetch-collections))
         (->> (rx/filter collections-fetched? s)
              (rx/take 1)
              (rx/ignore)))
      (rx/empty))))

(defn conditional-fetch
  []
  (ConditionalFetch.))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (r/navigate :dashboard/colors
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

;; --- Collections Fetched

(defrecord CollectionFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce assoc-collection state items)))

(defn collections-fetched
  [items]
  (CollectionFetched. items))

(defn collections-fetched?
  [v]
  (instance? CollectionFetched v))

;; --- Fetch Collections

(defrecord FetchCollections []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/color-collections)
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Collection Created

(defrecord CollectionCreated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (assoc-collection item)
        (assoc-in [:dashboard :colors :id] (:id item))
        (assoc-in [:dashboard :colors :type] :own))))

(defn collection-created
  [item]
  (CollectionCreated. item))

;; --- Create Collection

(defrecord CreateCollection []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [coll {:name "Unnamed collection"
                :id (uuid/random)}]
      (->> (rp/req :create/color-collection coll)
           (rx/map :payload)
           (rx/map collection-created)))))

(defn create-collection
  []
  (CreateCollection.))

;; --- Collection Updated

(defrecord CollectionUpdated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-collection state item)))

(defn collection-updated
  [item]
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [item (get-in state [:color-colls-by-id id])]
      (->> (rp/req :update/color-collection item)
           (rx/map :payload)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:color-colls-by-id id :name] name))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [item name]
  (RenameCollection. item name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (dissoc-collection state id))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :delete/color-collection id)
         (rx/ignore))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Replace Color

(defrecord ReplaceColor [id from to]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [replacer #(-> (disj % from) (conj to))]
      (update-in state [:color-colls-by-id id :data] (fnil replacer #{}))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [id from to] :as params}]
  (ReplaceColor. id from to))

;; --- Remove Color

(defrecord RemoveColors [id colors]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:color-colls-by-id id :data]
               #(set/difference % colors)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn remove-colors
  "Remove color in a collection."
  [id colors]
  (RemoveColors. id colors))

;; --- Select color

(defrecord SelectColor [color]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :colors :selected] conj color)))

(defrecord DeselectColor [color]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :colors :selected] disj color)))

(defrecord ToggleColorSelection [color]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (rx/of
       (if (selected color)
         (DeselectColor. color)
         (SelectColor. color))))))

(defn toggle-color-selection
  [color]
  {:pre [(color/hex? color)]}
  (ToggleColorSelection. color))

;; --- Delete Selected Colors

(defrecord DeleteSelectedColors []
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [{:keys [id selected]} (get-in state [:dashboard :colors])]
      (rx/of (remove-colors id selected)
             #(assoc-in % [:dashboard :colors :selected] #{})))))

(defn delete-selected-colors
  []
  (DeleteSelectedColors.))
