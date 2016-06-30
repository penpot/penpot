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
            [uxbox.main.state :as st]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state.colors :as stc]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize []
  rs/EffectEvent
  (-apply-effect [_ state]
    (when-not (seq (:colors-by-id state))
      (reset! st/loader true)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [colors (seq (:colors-by-id state))]
      (if colors
        (rx/empty)
        (rx/merge
         (rx/of (fetch-collections))
         (->> (rx/filter collections-fetched? s)
              (rx/take 1)
              (rx/do #(reset! st/loader false))
              (rx/ignore)))))))

(defn initialize
  []
  (Initialize.))

;; --- Collections Fetched

(defrecord CollectionFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stc/assoc-collection state items)))

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
        (stc/assoc-collection item)
        (assoc-in [:dashboard :collection-id] (:id item))
        (assoc-in [:dashboard :collection-type] :own))))

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
    (stc/assoc-collection state item)))

(defn collection-updated
  [item]
  (CollectionUpdated. item))


;; --- Update Collection

(defrecord UpdateCollection [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [item (get-in state [:colors-by-id id])]
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
    (assoc-in state [:colors-by-id id :name] name))

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
    (stc/dissoc-collection state id))

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
      (update-in state [:colors-by-id id :data] (fnil replacer #{}))))

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
    (update-in state [:colors-by-id id :data]
               #(set/difference % colors)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn remove-colors
  "Remove color in a collection."
  [id colors]
  (RemoveColors. id colors))
