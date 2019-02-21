;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.colors
  (:require [clojure.set :as set]
            [beicon.core :as rx]
            [uxbox.util.time :as dt]
            [uxbox.util.uuid :as uuid]
            [potok.core :as ptk]
            [uxbox.util.router :as r]
            [uxbox.util.color :as color]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-collections)
(declare persist-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  ptk/UpdateEvent
  (update [_ state]
    (let [type (or type :own)
          data {:type type
                :id id
                :selected #{}}]
      (-> state
          (assoc-in [:dashboard :colors] data)
          (assoc-in [:dashboard :section] :dashboard/colors))))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (fetch-collections))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (r/navigate :dashboard/colors
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

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
           (select-collection :own id))))

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
          store {:key "color-collections"
                 :version version
                 :value value}]
      (->> (rp/req :update/kvstore store)
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
    (let [type (get-in state [:dashboard :colors :type])]
      (rx/of (persist-collections)
             (select-collection type)))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Replace Color

(defrecord ReplaceColor [id from to]
  ptk/UpdateEvent
  (update [_ state]
    (let [replacer #(-> (disj % from) (conj to))]
      (update-in state [:colors-collections id :colors] (fnil replacer #{}))))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (persist-collections))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [id from to] :as params}]
  (ReplaceColor. id from to))

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

;; --- Copy Selected Icon

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

;; --- Move Selected Icon

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

;; --- Delete Selected Colors

(defrecord DeleteSelectedColors []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [id selected]} (get-in state [:dashboard :colors])]
      (rx/of (remove-colors id selected)
             #(assoc-in % [:dashboard :colors :selected] #{})))))

(defn delete-selected-colors
  []
  (DeleteSelectedColors.))
