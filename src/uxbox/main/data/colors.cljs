;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.colors
  (:require [clojure.set :as set]
            [beicon.core :as rx]
            [uxbox.util.datetime :as dt]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.util.color :as color]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-collections)
(declare persist-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [type (or type :own)
          data {:type type
                :id id
                :selected #{}}]
      (-> state
          (assoc-in [:dashboard :colors] data)
          (assoc-in [:dashboard :section] :dashboard/colors))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (fetch-collections))))

(defn initialize
  [type id]
  (Initialize. type id))

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

(defrecord CollectionsFetched [data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (assoc-in [:kvstore :color-collections] data)
        (update :color-colls-by-id merge (:value data)))))

(defn collections-fetched
  [data]
  {:pre [(map? data)]}
  (CollectionsFetched. data))

(defn collections-fetched?
  [v]
  (instance? CollectionsFetched v))

;; --- Fetch Collections

(defrecord FetchCollections []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/kvstore "color-collections")
         (rx/map :payload)
         (rx/map (fn [payload]
                   (if (nil? payload)
                     {:key "color-collections"
                      :value nil}
                     payload)))
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Create Collection

(defrecord CreateCollection [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [item {:name "Unnamed collection"
                :id id
                :created-at (dt/now)
                :type :own
                :colors #{}}]
      (assoc-in state [:color-colls-by-id id] item)))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (persist-collections)
           (select-collection :own id))))

(defn create-collection
  []
  (let [id (uuid/random)]
    (CreateCollection. id)))

;; --- Persist Collections

(defrecord PersistCollections []
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [builtin? #(= :builtin (:type %))
          xform (remove (comp builtin? second))
          value (->> (get state :color-colls-by-id)
                     (into {} xform))
          store (get-in state [:kvstore :color-collections])
          store (assoc store :value value)]
      (->> (rp/req :update/kvstore store)
           (rx/map :payload)
           (rx/map collections-fetched)))))

(defn persist-collections
  []
  (PersistCollections.))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:color-colls-by-id id :name] name))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (persist-collections))))

(defn rename-collection
  [item name]
  (RenameCollection. item name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :color-colls-by-id dissoc id))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [type (get-in state [:dashboard :colors :type])]
      (rx/of (persist-collections)
             (select-collection type)))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Replace Color

(defrecord ReplaceColor [id from to]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [replacer #(-> (disj % from) (conj to))]
      (update-in state [:color-colls-by-id id :colors] (fnil replacer #{}))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (persist-collections))))

(defn replace-color
  "Add or replace color in a collection."
  [{:keys [id from to] :as params}]
  (ReplaceColor. id from to))

;; --- Remove Color

(defrecord RemoveColors [id colors]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:color-colls-by-id id :colors]
               #(set/difference % colors)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (persist-collections))))

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

;; --- Copy Selected Icon

(defrecord CopySelected [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (update-in state [:color-colls-by-id id :colors] set/union selected)))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (persist-collections))))

(defn copy-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (CopySelected. id))

;; --- Move Selected Icon

(defrecord MoveSelected [from to]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [selected (get-in state [:dashboard :colors :selected])]
      (-> state
          (update-in [:color-colls-by-id from :colors] set/difference selected)
          (update-in [:color-colls-by-id to :colors] set/union selected))))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (persist-collections))))

(defn move-selected
  [from to]
  {:pre [(or (uuid? from) (nil? from))
         (or (uuid? to) (nil? to))]}
  (MoveSelected. from to))

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
