;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.images
  (:require [cuerdas.core :as str]
            [beicon.core :as rx]
            [promesa.core :as p]
            [uxbox.util.data :refer (jscoll->vec)]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.util.dom.files :as files]
            [uxbox.main.state :as st]
            [uxbox.main.repo :as rp]))

;; --- Initialize

(declare fetch-images)
(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [type (or type :own)
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :images] data)
          (assoc-in [:dashboard :section] :dashboard/images))))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/merge (rx/of (fetch-collections))
              (when (uuid? id)
                (rx/of (fetch-images id))))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (r/navigate :dashboard/images
                       {:type type :id id}))))

(defn select-collection
  ([type]
   (select-collection type nil))
  ([type id]
   {:pre [(keyword? type)]}
   (SelectCollection. type id)))

;; --- Color Collections Fetched

(defrecord CollectionsFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce (fn [state item]
              (let [id (:id item)
                    item (assoc item :type :own)]
                (assoc-in state [:image-colls-by-id id] item)))
            state
            items)))

(defn collections-fetched
  [items]
  (CollectionsFetched. items))

;; --- Fetch Color Collections

(defrecord FetchCollections []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/image-collections)
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Collection Created

(defrecord CollectionCreated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [item (assoc item :type :own)]
      (-> state
          (assoc-in [:image-colls-by-id (:id item)] item)
          (assoc-in [:dashboard :images :id] (:id item))
          (assoc-in [:dashboard :type] :own)))))

(defn collection-created
  [item]
  (CollectionCreated. item))

;; --- Create Collection

(defrecord CreateCollection []
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [coll {:name "Unnamed collection"
                :id (uuid/random)}]
      (->> (rp/req :create/image-collection coll)
           (rx/map :payload)
           (rx/map collection-created)))))

(defn create-collection
  []
  (CreateCollection.))

(defn collections-fetched?
  [v]
  (instance? CollectionsFetched v))

;; --- Collection Updated

(defrecord CollectionUpdated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:image-colls-by-id (:id item)]  merge item)))

(defn collection-updated
  [item]
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [item (get-in state [:image-colls-by-id id])]
      (->> (rp/req :update/image-collection item)
           (rx/map :payload)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:image-colls-by-id id :name] name))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [id name]
  (RenameCollection. id name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :image-colls-by-id dissoc id))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :delete/image-collection id)
         (rx/ignore))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Image Created

(defrecord ImageCreated [item]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update state :images-by-id assoc (:id item) item)))

(defn image-created
  [item]
  (ImageCreated. item))

;; --- Create Image

(def allowed-file-types #{"image/jpeg" "image/png"})

(defrecord CreateImages [id files]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(image-size [file]
              (->> (files/get-image-size file)
                   (rx/map (partial vector file))))
            (allowed-file? [file]
              (contains? allowed-file-types (.-type file)))
            (prepare [[file [width height]]]
              {:collection id
               :mimetype (.-type file)
               :id (uuid/random)
               :file file
               :width width
               :height height})]
      (->> (rx/from-coll (jscoll->vec files))
           (rx/filter allowed-file?)
           (rx/flat-map image-size)
           (rx/map prepare)
           (rx/flat-map #(rp/req :create/image %))
           (rx/map :payload)
           (rx/map image-created)))))

(defn create-images
  [id files]
  {:pre [(or (uuid? id) (nil? id))]}
  (CreateImages. id files))

;; --- Images Fetched

(defrecord ImagesFetched [items]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce (fn [state {:keys [id] :as image}]
              (assoc-in state [:images-by-id id] image))
            state
            items)))

(defn images-fetched
  [items]
  (ImagesFetched. items))

;; --- Fetch Images

(defrecord FetchImages [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [params {:coll id}]
      (->> (rp/req :fetch/images params)
           (rx/map :payload)
           (rx/map images-fetched)))))

(defn fetch-images
  "Fetch a list of images of the selected collection"
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (FetchImages. id))

;; --- Fetch Image

(declare image-fetched)

(defrecord FetchImage [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [existing (get-in state [:images-by-id id])]
      (if existing
        (rx/empty)
        (->> (rp/req :fetch/image {:id id})
             (rx/map :payload)
             (rx/map image-fetched))))))

(defn fetch-image
  "Conditionally fetch image by its id. If image
  is already loaded, this event is noop."
  [id]
  {:pre [(uuid? id)]}
  (FetchImage. id))

;; --- Image Fetched

(defrecord ImageFetched [image]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [id (:id image)]
      (update state :images-by-id assoc id image))))

(defn image-fetched
  [image]
  {:pre [(map? image)]}
  (ImageFetched. image))

;; --- Delete Images

(defrecord DeleteImage [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (update :images-by-id dissoc id)
        (update-in [:dashboard :images :selected] disj id)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :delete/image id)
         (rx/ignore))))

(defn delete-image
  [id]
  {:pre [(uuid? id)]}
  (DeleteImage. id))

;; --- Select image

(defrecord SelectImage [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :images :selected] conj id)))

(defrecord DeselectImage [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :images :selected] disj id)))

(defrecord ToggleImageSelection [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/of
       (if (selected id)
         (DeselectImage. id)
         (SelectImage. id))))))

(defn toggle-image-selection
  [id]
  (ToggleImageSelection. id))

;; --- Delete Selected

(defrecord DeleteSelected []
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (->> (rx/from-coll selected)
           (rx/map delete-image)))))

(defn delete-selected
  []
  (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter]
  rs/UpdateEvent
  (-apply-update [_ state]
    (update-in state [:dashboard :images] merge
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter] :as opts}]
  (UpdateOpts. order filter))
