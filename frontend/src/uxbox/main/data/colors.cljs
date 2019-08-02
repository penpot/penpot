;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.colors
  (:require
   [beicon.core :as rx]
   [clojure.set :as set]
   [potok.core :as ptk]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.color :as color]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]
   [uxbox.util.uuid :as uuid]))

;; --- Initialize

(declare fetch-collections)
(declare persist-collections)
(declare collections-fetched?)

(defrecord Initialize []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:dashboard :colors] {:selected #{}})))

(defn initialize
  []
  (Initialize.))

;; --- Collections Fetched

(defrecord CollectionsFetched [data]
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [version value]} data]
      (-> state
          (update :colors-collections merge value)
          (assoc ::version version)))))

(defn collections-fetched
  [data]
  {:pre [(map? data)]}
  (CollectionsFetched. data))

(defn collections-fetched?
  [v]
  (instance? CollectionsFetched v))

;; --- Fetch Collections

(defrecord FetchCollections []
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/kvstore "color-collections")
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Create Collection

(defrecord CreateCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [item {:name (tr "ds.default-library-title" (gensym "c"))
                :id id
                :created-at (dt/now)
                :type :own
                :colors #{}}]
      (assoc-in state [:colors-collections id] item)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-collections)
           (rt/nav :dashboard/colors nil {:type :own :id id}))))

(defn create-collection
  []
  (let [id (uuid/random)]
    (CreateCollection. id)))

;; --- Persist Collections

(defrecord PersistCollections []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [builtin? #(= :builtin (:type %))
          xform (remove (comp builtin? second))
          version (or (get state ::version) -1)
          value (->> (get state :colors-collections)
                     (into {} xform))
          data {:id "color-collections"
                :version version
                :value value}]
      (->> (rp/req :update/kvstore data)
           (rx/map :payload)
           (rx/map collections-fetched)))))

(defn persist-collections
  []
  (PersistCollections.))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:colors-collections id :name] name))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (persist-collections))))

(defn rename-collection
  [item name]
  (RenameCollection. item name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (update state :colors-collections dissoc id))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (persist-collections))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Replace Color

(defrecord AddColor [coll-id color]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:colors-collections coll-id :colors] set/union #{color}))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (persist-collections))))

(defn add-color
  "Add or replace color in a collection."
  [coll-id color]
  (AddColor. coll-id color))

;; --- Remove Color

(defrecord RemoveColors [id colors]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:colors-collections id :colors]
               #(set/difference % colors)))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (persist-collections))))

(defn remove-colors
  "Remove color in a collection."
  [id colors]
  (RemoveColors. id colors))

;; --- Select color

(defrecord SelectColor [color]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :colors :selected] conj color)))

(defrecord DeselectColor [color]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :colors :selected] disj color)))

(defrecord ToggleColorSelection [color]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (rx/of
       (if (selected color)
         (DeselectColor. color)
         (SelectColor. color))))))

(defn toggle-color-selection
  [color]
  {:pre [(color/hex? color)]}
  (ToggleColorSelection. color))

;; --- Copy Selected Color

(defrecord CopySelected [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (update-in state [:colors-collections id :colors] set/union selected)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-collections))))

(defn copy-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (CopySelected. id))

;; --- Move Selected Color

(defrecord MoveSelected [from to]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (-> state
          (update-in [:colors-collections from :colors] set/difference selected)
          (update-in [:colors-collections to :colors] set/union selected))))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-collections))))

(defn move-selected
  [from to]
  {:pre [(or (uuid? from) (nil? from))
         (or (uuid? to) (nil? to))]}
  (MoveSelected. from to))

;; --- Delete Colors

(defrecord DeleteColors [coll-id colors]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:dashboard :colors :selected] #{}))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (remove-colors coll-id colors))))

(defn delete-colors
  [coll-id colors]
  (DeleteColors. coll-id colors))
