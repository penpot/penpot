;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  (:require
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [potok.core :as ptk]
   [uxbox.main.repo :as rp]
   [uxbox.main.store :as st]
   [uxbox.util.spec :as us]
   [uxbox.util.timers :as ts]
   [uxbox.util.data :refer [index-by-id]]))

;; --- Specs

(s/def ::grid-x-axis number?)
(s/def ::grid-y-axis number?)
(s/def ::grid-color string?)
(s/def ::background string?)
(s/def ::background-opacity number?)
(s/def ::grid-alignment boolean?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::layout string?)

(s/def ::metadata
  (s/keys :req-un [::width ::height]
          :opt-un [::grid-y-axis
                   ::grid-x-axis
                   ::grid-color
                   ::grid-alignment
                   ::order
                   ::background
                   ::background-opacity
                   ::layout]))

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::version integer?)
(s/def ::project uuid?)
(s/def ::user uuid?)
(s/def ::created-at inst?)
(s/def ::modified-at inst?)
(s/def ::shapes
  (-> (s/coll-of uuid? :kind vector?)
      (s/nilable)))

(s/def ::page-entity
  (s/keys :req-un [::id
                   ::name
                   ::project
                   ::version
                   ::created-at
                   ::modified-at
                   ::user
                   ::metadata
                   ::shapes]))

;; TODO: add interactions to spec

;; --- Protocols

(defprotocol IPageUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

(defprotocol IMetadataUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

;; --- Helpers

(defn pack-page
  "Return a packed version of page object ready
  for send to remore storage service."
  [state id]
  (letfn [(pack-shapes [ids]
            (mapv #(get-in state [:shapes %]) ids))]
    (let [page (get-in state [:pages id])
          data {:shapes (pack-shapes (:shapes page))}]
      (-> page
          (assoc :data data)
          (dissoc :shapes)))))

(defn unpack-page
  "Unpacks packed page object and assocs it to the
  provided state."
  [state {:keys [id data] :as page}]
  (let [shapes-data (:shapes data [])
        shapes (mapv :id shapes-data)
        shapes-map (index-by-id shapes-data)

        page (-> page
                 (dissoc :data)
                 (assoc :shapes shapes))]
    (-> state
        (update :shapes merge shapes-map)
        (update :pages assoc id page))))

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (let [shapes (get state :shapes)]
    (-> state
        (update :pages dissoc id)
        (update :packed-pages dissoc id)
        (assoc :shapes (reduce-kv (fn [acc k v]
                                    (if (= (:page v) id)
                                      (dissoc acc k)
                                      acc))
                                  shapes
                                  shapes)))))

(defn assoc-packed-page
  [state {:keys [id] :as page}]
  (assoc-in state [:packed-pages id] page))

(defn dissoc-packed-page
  [state id]
  (update state :packed-pages dissoc id))

;; --- Pages Fetched

(deftype PagesFetched [id pages]
  IDeref
  (-deref [_] (list id pages))

  ptk/UpdateEvent
  (update [_ state]
    (let [get-order #(get-in % [:metadata :order])
          pages (sort-by get-order pages)
          page-ids (into [] (map :id) pages)]
    (as-> state $
      (assoc-in $ [:projects id :pages] page-ids)
      ;; TODO: this is a workaround
      (assoc-in $ [:projects id :page-id] (first page-ids))
      (reduce unpack-page $ pages)
      (reduce assoc-packed-page $ pages)))))

(defn pages-fetched
  [id pages]
  {:pre [(uuid? id) (coll? pages)]}
  (PagesFetched. id pages))

(defn pages-fetched?
  [v]
  (instance? PagesFetched v))

;; --- Fetch Pages (by project id)

(deftype FetchPages [id]
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/pages-by-project {:project id})
         (rx/map :payload)
         (rx/map #(pages-fetched id %)))))

(defn fetch-pages
  [id]
  {:pre [(uuid? id)]}
  (FetchPages. id))

;; --- Page Created

(declare reorder-pages)

(deftype PageCreated [data]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (unpack-page data)
        (assoc-packed-page data)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (reorder-pages (:project data)))))

(s/def ::page-created
  (s/keys :req-un [::id
                   ::name
                   ::project
                   ::metadata]))

(defn page-created
  [data]
  {:pre [(us/valid? ::page-created data)]}
  (PageCreated. data))

;; --- Create Page

(deftype CreatePage [name project width height layout]
  ptk/WatchEvent
  (watch [this state s]
    (let [params {:name name
                  :project project
                  :data {}
                  :metadata {:width width
                             :height height
                             :layout layout
                             :order -100}}]
      (->> (rp/req :create/page params)
           (rx/map :payload)
           (rx/map page-created)))))

(s/def ::create-page
  (s/keys :req-un [::name
                   ::project
                   ::width
                   ::height
                   ::layout]))

(defn create-page
  [{:keys [name project width height layout] :as data}]
  {:pre [(us/valid? ::create-page data)]}
  (CreatePage. name project width height layout))

;; --- Page Persisted

(deftype PagePersisted [data]
  IDeref
  (-deref [_] data)

  ptk/UpdateEvent
  (update [_ state]
    (let [{:keys [id version]} data]
      (-> state
          (assoc-in [:pages id :version] version)
          (assoc-packed-page data)))))

(defn- page-persisted?
  [event]
  (instance? PagePersisted event))

;; TODO: add page spec

(defn page-persisted
  [data]
  {:pre [(map? data)]}
  (PagePersisted. data))

;; --- Persist Page

(deftype PersistPage [id on-success]
  ptk/WatchEvent
  (watch [this state s]
    (let [page (get-in state [:pages id])]
      (if (:history page)
        (rx/empty)
        (let [page (pack-page state id)]
          (->> (rp/req :update/page page)
               (rx/map :payload)
               (rx/do #(when (fn? on-success)
                         (ts/schedule-on-idle on-success)))
               (rx/map page-persisted)))))))

(defn persist-page?
  [v]
  (instance? PersistPage v))

(defn persist-page
  ([id]
   {:pre [(uuid? id)]}
   (PersistPage. id (constantly nil)))
  ([id on-success]
   {:pre [(uuid? id)]}
   (PersistPage. id on-success)))

;; --- Page Metadata Persisted

(deftype MetadataPersisted [id data]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:pages id :version] (:version data))))

(s/def ::metadata-persisted-event
  (s/keys :req-un [::id ::version]))

(defn metadata-persisted?
  [v]
  (instance? MetadataPersisted v))

(defn metadata-persisted
  [{:keys [id] :as data}]
  {:pre [(us/valid? ::metadata-persisted-event data)]}
  (MetadataPersisted. id data))

;; --- Persist Page Metadata

;; This is a simplified version of `PersistPage` event
;; that does not sends the heavyweiht `:data` attribute
;; and only serves for update other page data.

(defn persist-metadata
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page (get-in state [:pages id])]
        (->> (rp/req :update/page-metadata page)
             (rx/map :payload)
             (rx/map metadata-persisted))))))

;; --- Update Page

(deftype UpdatePage [id data]
  IPageUpdate
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:pages id] merge (dissoc data :id :version))))

(defn update-page
  [id data]
  {:pre [(uuid? id) (us/valid? ::page-entity data)]}
  (UpdatePage. id data))

;; --- Update Page Metadata

(deftype UpdateMetadata [id metadata]
  IMetadataUpdate
  ptk/UpdateEvent
  (update [this state]
    (assoc-in state [:pages id :metadata] metadata)))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (us/valid? ::metadata metadata)]}
  (UpdateMetadata. id metadata))

;; --- Reorder Pages
;;
;; A post processing event that normalizes the
;; page order numbers after a user sorting
;; operation for a concrete project.

(defn reorder-pages
  [project-id]
  {:pre [(uuid? project-id)]}
  (reify
    ptk/UpdateEvent
    (update [this state]
      (let [page-ids (get-in state [:projects project-id :pages])]
        (reduce (fn [state [index id]]
                  (assoc-in state [:pages id :metadata :order] index))
                ;; TODO: this is workaround
                (assoc-in state [:projects project-id :page-id] (first page-ids))
                (map-indexed vector page-ids))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects project-id :pages])]
        (->> (rx/from-coll page-ids)
             (rx/map persist-metadata))))))

;; --- Move Page (Ordering)

(defn move-page
  [{:keys [page-id project-id index] :as params}]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pages (get-in state [:projects project-id :pages])
            pages (into [] (remove #(= % page-id)) pages)
            [before after] (split-at index pages)
            pages (vec (concat before [page-id] after))]
        (assoc-in state [:projects project-id :pages] pages)))))

;; --- Persist Page Form
;;
;; A specialized event for persist data
;; from the update page form.

(deftype PersistPageUpdateForm [id name width height layout]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (-> (get-in state [:pages id])
                   (assoc-in [:name] name)
                   (assoc-in [:metadata :width] width)
                   (assoc-in [:metadata :height] height)
                   (assoc-in [:metadata :layout] layout))]
      (rx/of (update-page id page)))))

(s/def ::persist-page-update-form
  (s/keys :req-un [::name ::width ::height ::layout]))

(defn persist-page-update-form
  [id {:keys [name width height layout] :as data}]
  {:pre [(uuid? id) (us/valid? ::persist-page-update-form data)]}
  (PersistPageUpdateForm. id name width height layout))

;; --- Delete Page (by id)

(deftype DeletePage [id callback]
  ptk/WatchEvent
  (watch [_ state s]
    (letfn [(on-success [_]
              #(purge-page % id))]
      (->> (rp/req :delete/page id)
           (rx/map on-success)
           (rx/tap callback)
           (rx/filter identity)))))

(defn delete-page
  ([id] (DeletePage. id (constantly nil)))
  ([id callback] (DeletePage. id callback)))

;; --- Watch Page Changes

(deftype WatchPageChanges [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [stopper (->> stream
                       (rx/filter #(= % ::stop-page-watcher))
                       (rx/take 1))]
      (rx/merge
       (->> stream
            (rx/filter #(or (satisfies? IPageUpdate %)
                            (= ::page-update %)))
            (rx/take-until stopper)
            (rx/debounce 1000)
            (rx/mapcat #(rx/merge (rx/of (persist-page id))
                                  (->> (rx/filter page-persisted? stream)
                                       (rx/take 1)
                                       (rx/ignore)))))
       (->> stream
            (rx/filter #(satisfies? IMetadataUpdate %))
            (rx/take-until stopper)
            (rx/debounce 1000)
            (rx/mapcat #(rx/merge (rx/of (persist-metadata id))
                                  (->> (rx/filter metadata-persisted? stream)
                                       (rx/take 1)
                                       (rx/ignore)))))))))

(defn watch-page-changes
  [id]
  (WatchPageChanges. id))
