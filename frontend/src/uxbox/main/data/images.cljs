;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.images
  (:require [cljs.spec.alpha :as s]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.data :refer (jscoll->vec)]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.time :as ts]
            [uxbox.util.spec :as us]
            [uxbox.util.router :as r]
            [uxbox.util.files :as files]))

;; --- Specs

(s/def ::name string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::modified-at inst?)
(s/def ::created-at inst?)
(s/def ::mimetype string?)
(s/def ::thumbnail us/url-str?)
(s/def ::id uuid?)
(s/def ::version integer?)
(s/def ::url us/url-str?)
(s/def ::collection (s/nilable uuid?))
(s/def ::user uuid?)

(s/def ::collection-entity
  (s/keys :req-un [::id
                   ::name
                   ::created-at
                   ::modified-at
                   ::user
                   ::version]))

(s/def ::image-entity
  (s/keys :req-un [::id
                   ::name
                   ::width
                   ::height
                   ::created-at
                   ::modified-at
                   ::mimetype
                   ::thumbnail
                   ::url
                   ::version
                   ::collection
                   ::user]))

;; --- Initialize

(declare fetch-images)
(declare fetch-collections)
(declare collections-fetched?)

(defrecord Initialize [type id]
  ptk/UpdateEvent
  (update [_ state]
    (let [type (or type :own)
          data {:type type :id id :selected #{}}]
      (-> state
          (assoc-in [:dashboard :images] data)
          (assoc-in [:dashboard :section] :dashboard/images))))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/merge (rx/of (fetch-collections))
              (rx/of (fetch-images id)))))

(defn initialize
  [type id]
  (Initialize. type id))

;; --- Select a Collection

(defrecord SelectCollection [type id]
  ptk/WatchEvent
  (watch [_ state stream]
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
  ptk/UpdateEvent
  (update [_ state]
    (reduce (fn [state {:keys [id user] :as item}]
              (let [type (if (uuid/zero? (:user item)) :builtin :own)
                    item (assoc item :type type)]
                (assoc-in state [:images-collections id] item)))
            state
            items)))

(defn collections-fetched
  [items]
  {:pre [(us/valid? (s/every ::collection-entity) items)]}
  (CollectionsFetched. items))

;; --- Fetch Color Collections

(defrecord FetchCollections []
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/image-collections)
         (rx/map :payload)
         (rx/map collections-fetched))))

(defn fetch-collections
  []
  (FetchCollections.))

;; --- Collection Created

(defrecord CollectionCreated [item]
  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [id] :as item} (assoc item :type :own)]
      (update state :images-collections assoc id item)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (select-collection :own (:id item)))))

(defn collection-created
  [item]
  {:pre [(us/valid? ::collection-entity item)]}
  (CollectionCreated. item))

;; --- Create Collection

(defrecord CreateCollection []
  ptk/WatchEvent
  (watch [_ state s]
    (let [coll {:name (tr "ds.default-library-title" (gensym "c"))
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
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:images-collections (:id item)]  merge item)))

(defn collection-updated
  [item]
  {:pre [(us/valid? ::collection-entity item)]}
  (CollectionUpdated. item))

;; --- Update Collection

(defrecord UpdateCollection [id]
  ptk/WatchEvent
  (watch [_ state s]
    (let [item (get-in state [:images-collections id])]
      (->> (rp/req :update/image-collection item)
           (rx/map :payload)
           (rx/map collection-updated)))))

(defn update-collection
  [id]
  (UpdateCollection. id))

;; --- Rename Collection

(defrecord RenameCollection [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:images-collections id :name] name))

  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (update-collection id))))

(defn rename-collection
  [id name]
  (RenameCollection. id name))

;; --- Delete Collection

(defrecord DeleteCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (update state :images-collections dissoc id))

  ptk/WatchEvent
  (watch [_ state s]
    (let [type (get-in state [:dashboard :images :type])]
      (->> (rp/req :delete/image-collection id)
           (rx/map #(select-collection type))))))

(defn delete-collection
  [id]
  (DeleteCollection. id))

;; --- Image Created

(defrecord ImageCreated [item]
  ptk/UpdateEvent
  (update [_ state]
    (update state :images assoc (:id item) item)))

(defn image-created
  [item]
  {:pre [(us/valid? ::image-entity item)]}
  (ImageCreated. item))

;; --- Create Image

(def allowed-file-types #{"image/jpeg" "image/png"})

(defrecord CreateImages [id files on-uploaded]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:dashboard :images :uploading] true))

  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(image-size [file]
              (->> (files/get-image-size file)
                   (rx/map (partial vector file))))
            (allowed-file? [file]
              (contains? allowed-file-types (.-type file)))
            (finalize-upload [state]
              (assoc-in state [:dashboard :images :uploading] false))
            (prepare [[file [width height]]]
              {:collection id
               :mimetype (.-type file)
               :id (uuid/random)
               :file file
               :width width
               :height height})]
      (->> (rx/from-coll files)
           (rx/filter allowed-file?)
           (rx/mapcat image-size)
           (rx/map prepare)
           (rx/mapcat #(rp/req :create/image %))
           (rx/map :payload)
           (rx/reduce conj [])
           (rx/do #(st/emit! finalize-upload))
           (rx/do on-uploaded)
           (rx/mapcat identity)
           (rx/map image-created)))))

(defn create-images
  ([id files]
   {:pre [(or (uuid? id) (nil? id))]}
   (CreateImages. id files (constantly nil)))
  ([id files on-uploaded]
   {:pre [(or (uuid? id) (nil? id)) (fn? on-uploaded)]}
   (CreateImages. id files on-uploaded)))

;; --- Image Updated

(defrecord ImagePersisted [id data]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:images id] data)))

(defn image-persisted
  [{:keys [id] :as data}]
  {:pre [(map? data) (uuid? id)]}
  (ImagePersisted. id data))

;; --- Update Image

(defrecord PersistImage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [image (get-in state [:images id])]
      (->> (rp/req :update/image image)
           (rx/map :payload)
           (rx/map image-persisted)))))

(defn persist-image
  [id]
  {:pre [(uuid? id)]}
  (PersistImage. id))

;; --- Images Fetched

(defrecord ImagesFetched [items]
  ptk/UpdateEvent
  (update [_ state]
    (reduce (fn [state {:keys [id] :as image}]
              (assoc-in state [:images id] image))
            state
            items)))

(defn images-fetched
  [items]
  (ImagesFetched. items))

;; --- Fetch Images

(defrecord FetchImages [id]
  ptk/WatchEvent
  (watch [_ state s]
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
  ptk/WatchEvent
  (watch [_ state stream]
    (let [existing (get-in state [:images id])]
      (if existing
        (rx/empty)
        (->> (rp/req :fetch/image {:id id})
             (rx/catch rp/client-error? #(rx/empty))
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
  ptk/UpdateEvent
  (update [_ state]
    (let [id (:id image)]
      (update state :images assoc id image))))

(defn image-fetched
  [image]
  {:pre [(map? image)]}
  (ImageFetched. image))

;; --- Delete Images

(defrecord DeleteImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (update :images dissoc id)
        (update-in [:dashboard :images :selected] disj id)))

  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :delete/image id)
         (rx/ignore))))

(defn delete-image
  [id]
  {:pre [(uuid? id)]}
  (DeleteImage. id))

;; --- Rename Image

(defrecord RenameImage [id name]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:images id :name] name))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-image id))))

(defn rename-image
  [id name]
  {:pre [(uuid? id) (string? name)]}
  (RenameImage. id name))

;; --- Select image

(defrecord SelectImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images :selected] conj id)))

(defrecord DeselectImage [id]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images :selected] disj id)))

(defrecord ToggleImageSelection [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/of
       (if (selected id)
         (DeselectImage. id)
         (SelectImage. id))))))

(defn deselect-image
  [id]
  {:pre [(uuid? id)]}
  (DeselectImage. id))

(defn toggle-image-selection
  [id]
  (ToggleImageSelection. id))

;; --- Copy Selected Image

(defrecord CopySelected [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/merge
       (->> (rx/from-coll selected)
            (rx/flat-map #(rp/req :copy/image {:id % :collection id}))
            (rx/map :payload)
            (rx/map image-created))
       (->> (rx/from-coll selected)
            (rx/map deselect-image))))))

(defn copy-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (CopySelected. id))

;; --- Move Selected Image

(defrecord MoveSelected [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:dashboard :images :selected])]
      (reduce (fn [state image]
                (assoc-in state [:images image :collection] id))
              state
              selected)))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (rx/merge
       (->> (rx/from-coll selected)
            (rx/map persist-image))
       (->> (rx/from-coll selected)
            (rx/map deselect-image))))))

(defn move-selected
  [id]
  {:pre [(or (uuid? id) (nil? id))]}
  (MoveSelected. id))

;; --- Delete Selected

(defrecord DeleteSelected []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [selected (get-in state [:dashboard :images :selected])]
      (->> (rx/from-coll selected)
           (rx/map delete-image)))))

(defn delete-selected
  []
  (DeleteSelected.))

;; --- Update Opts (Filtering & Ordering)

(defrecord UpdateOpts [order filter edition]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:dashboard :images] merge
               {:edition edition}
               (when order {:order order})
               (when filter {:filter filter}))))

(defn update-opts
  [& {:keys [order filter edition]
      :or {edition false}
      :as opts}]
  (UpdateOpts. order filter edition))
