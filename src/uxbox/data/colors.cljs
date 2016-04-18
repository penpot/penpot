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

;; --- Color Collections Fetched

(defn color-collections-fetched
  [color-collections]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (reduce stc/assoc-color-collection state color-collections))))

;; --- Fetch Color Collections

(defn fetch-color-collections
  []
  (reify
    rs/WatchEvent
    (-apply-watch [_ state s]
      (->> (rp/req :fetch/color-collections)
           (rx/map :payload)
           (rx/map color-collections-fetched)))))

;; --- Color Collection Created

(defn color-collection-created
  [color-collection]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (-> state
        (stc/assoc-color-collection color-collection)
        (assoc-in [:dashboard :collection-id] (:id color-collection))
        (assoc-in [:dashboard :collection-type] :own)))))

;; --- Create Color Collection

(defn create-color-collection
  []
  (reify
    rs/WatchEvent
    (-apply-watch [this state s]
      (letfn [(on-success [{coll :payload}]
                (rx/of
                 (color-collection-created coll)))]
        (->> (rp/req :create/color-collection {:name "Unnamed collection" :id (uuid/random) :data #{}})
             (rx/mapcat on-success))))))

;; --- Color Collection Changed

(defn color-collection-changed
  [color-collection]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (stc/assoc-color-collection state color-collection))))

;; --- Rename Color Collection

(defn rename-color-collection
  [coll name]
  (reify
    rs/WatchEvent
    (-apply-watch [this state s]
      (letfn [(on-success [{coll :payload}]
                (rx/of
                 (color-collection-changed coll)))]
        (->> (rp/req :update/color-collection (assoc coll :name name))
             (rx/mapcat on-success))))))

;; --- Delete Color Collection

(defrecord DeleteColorCollection [id callback]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stc/dissoc-color-collection % id)))]
      (->> (rp/req :delete/color-collection id)
           (rx/map on-success)
           (rx/tap callback)
           (rx/filter identity)))))

(defn delete-color-collection
  ([id] (DeleteColorCollection. id (constantly nil)))
  ([id callback] (DeleteColorCollection. id callback)))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [id from to coll] :as params}]
  (reify
    rs/WatchEvent
    (-apply-watch [this state s]
      (letfn [(on-success [{coll :payload}]
                (rx/of
                 (color-collection-changed coll)))]
        (->> (rp/req :update/color-collection (update coll :data
                                                #(-> % (disj from) (conj to))))
             (rx/mapcat on-success))))))

(defn remove-colors
  "Remove color in a collection."
  [{:keys [colors coll] :as params}]
  (reify
    rs/WatchEvent
    (-apply-watch [this state s]
      (letfn [(on-success [{coll :payload}]
                (rx/of
                 (color-collection-changed coll)))]
        (->> (rp/req :update/color-collection (update coll :data #(clojure.set/difference % colors)))
             (rx/mapcat on-success))))))
