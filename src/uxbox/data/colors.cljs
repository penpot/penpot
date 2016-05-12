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
    (letfn [(on-success [{coll :payload}]
              (rx/of
               (collection-created coll)))]
      (->> (rp/req :create/color-collection {:name "Unnamed collection" :id (uuid/random) :data #{}})
           (rx/mapcat on-success)))))

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
    (letfn [(on-success [{coll :payload}]
              (rx/of
               (collection-changed coll)))]
      (->> (rp/req :update/color-collection (assoc item :name name))
           (rx/mapcat on-success)))))

(defn rename-collection
  [item name]
  (RenameCollection. item name))

;; --- Delete Collection

(defrecord DeleteCollection [id callback]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stc/dissoc-collection % id)))]
      (->> (rp/req :delete/color-collection id)
           (rx/map on-success)
           (rx/tap callback)
           (rx/filter identity)))))

(defn delete-collection
  ([id] (DeleteCollection. id (constantly nil)))
  ([id callback] (DeleteCollection. id callback)))

;; --- Replace Color

(defrecord ReplaceColor [coll from to]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{coll :payload}]
              (rx/of
               (collection-changed coll)))]
      (->> (rp/req :update/color-collection (update coll :data
                                                    #(-> % (disj from) (conj to))))
           (rx/mapcat on-success)))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [from to coll] :as params}]
  (ReplaceColor. coll from to))

;; --- Remove Color

(defrecord RemoveColors [colors coll]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{coll :payload}]
              (rx/of
               (collection-changed coll)))]
      (->> (rp/req :update/color-collection (update coll :data #(clojure.set/difference % colors)))
           (rx/mapcat on-success)))))

(defn remove-colors
  "Remove color in a collection."
  [{:keys [colors coll] :as params}]
  (RemoveColors. colors coll))
