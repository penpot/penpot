;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.colors
  (:require [beicon.core :as rx]
            [uuid.core :as uuid]
            [uxbox.rstore :as rs]
            [uxbox.state.colors :as stc]
            [uxbox.repo :as rp]))

;; --- Collections Fetched

(defrecord CollectionFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stc/assoc-collection state items)))

(defn collections-fetched
  [items]
  (CollectionFetched. items))

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
  (-apply-watch [this state s]
    (let [coll {:name "Unnamed collection"
                :id (uuid/random)}]
      (->> (rp/req :create/color-collection coll)
           (rx/map :payload)
           (rx/map collection-created)))))

(defn create-collection
  []
  (CreateCollection.))

;; --- Collection Changed

(defrecord CollectionChanged [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (stc/assoc-collection state item)))

(defn collection-changed
  [item]
  (CollectionChanged. item))

;; --- Rename Collection

(defrecord RenameCollection [item name]
  rs/WatchEvent
  (-apply-watch [this state s]
    (->> (rp/req :update/color-collection (assoc item :name name))
         (rx/map :payload)
         (rx/map collection-changed))))

(defn rename-collection
  [item name]
  (RenameCollection. item name))

;; --- Delete Collection

(defrecord DeleteCollection [id callback]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :delete/color-collection id)
         (rx/map (constantly #(stc/dissoc-collection % id)))
         (rx/tap callback)
         (rx/filter identity))))

(defn delete-collection
  ([id] (DeleteCollection. id (constantly nil)))
  ([id callback] (DeleteCollection. id callback)))

;; --- Replace Color

(defrecord ReplaceColor [coll from to]
  rs/WatchEvent
  (-apply-watch [this state s]
    (let [coll (update coll :data #(-> % (disj from) (conj to)))]
      (->> (rp/req :update/color-collection coll)
           (rx/map :payload)
           (rx/map collection-changed)))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [from to coll] :as params}]
  (ReplaceColor. coll from to))

;; --- Remove Color

(defrecord RemoveColors [colors coll]
  rs/WatchEvent
  (-apply-watch [this state s]
    (let [coll (update coll :data #(clojure.set/difference % colors))]
      (->> (rp/req :update/color-collection coll)
           (rx/map :payload)
           (rx/map collection-changed)))))

(defn remove-colors
  "Remove color in a collection."
  [{:keys [colors coll] :as params}]
  (RemoveColors. colors coll))
