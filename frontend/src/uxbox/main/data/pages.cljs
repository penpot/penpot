;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  (:require [cljs.spec :as s]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.main.lenses :as ul]
            [uxbox.util.spec :as us]
            [uxbox.util.router :as r]
            [uxbox.util.timers :as ts]
            [uxbox.util.time :as dt]))

;; --- Specs

(s/def ::grid-x-axis number?)
(s/def ::grid-y-axis number?)
(s/def ::grid-color us/color?)
(s/def ::background us/color?)
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

;; TODO: make sure remove all :tmp-* related attrs from shape

(defn pack-page-shapes
  "Create a hash-map of shapes indexed by their id that belongs
  to the provided page."
  [state page]
  (let [lookup-shape-xf (map #(get-in state [:shapes %]))]
    (reduce (fn reducer [acc {:keys [id type items] :as shape}]
              (let [shape (assoc shape :page (:id page))]
                (cond
                  (= type :group)
                  (reduce reducer
                          (assoc acc id shape)
                          (sequence lookup-shape-xf items))

                  (uuid? id)
                  (assoc acc id shape)

                  :else acc)))
            {}
            (sequence lookup-shape-xf (:shapes page)))))

(defn pack-page
  "Return a packed version of page object ready
  for send to remore storage service."
  [state id]
  (let [page (get-in state [:pages id])
        shapes (pack-page-shapes state page)]
    (-> page
        (assoc-in [:data :shapes] (vec (:shapes page)))
        (assoc-in [:data :shapes-map] shapes)
        (dissoc :shapes))))

(defn assoc-page
  "Unpacks packed page object and assocs it to the
  provided state."
  [state {:keys [id data] :as page}]
  (let [shapes (:shapes data)
        shapes-map (:shapes-map data)
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

;; --- Update Project main page
;;
;; A event that handles the project main page
;; update based on the user selected page ordering.

(deftype UpdateProjectMainPage [id]
  ptk/UpdateEvent
  (update [_ state]
    (let [get-order #(get-in % [:metadata :order])
          page-id (->> (vals (:pages state))
                       (filter #(= id (:project %)))
                       (sort-by get-order)
                       (map :id)
                       (first))]
      (assoc-in state [:projects id :page-id] page-id))))

(defn update-project-main-page
  [id]
  {:pre [(uuid? id)]}
  (UpdateProjectMainPage. id))

;; --- Pages Fetched

(deftype PagesFetched [id pages]
  IDeref
  (-deref [_] (list id pages))

  ptk/UpdateEvent
  (update [_ state]
    (as-> state $
      (reduce assoc-page $ pages)
      (reduce assoc-packed-page $ pages)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (update-project-main-page id))))

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
        (assoc-page data)
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
                         (ts/schedule 0 on-success)))
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
  (instance? MetadataPersisted. v))

(defn metadata-persisted
  [{:keys [id] :as data}]
  {:pre [(us/valid? ::metadata-persisted-event data)]}
  (MetadataPersisted. id data))

;; --- Persist Page Metadata

;; This is a simplified version of `PersistPage` event
;; that does not sends the heavyweiht `:data` attribute
;; and only serves for update other page data.

(deftype PersistMetadata [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (get-in state [:pages id])]
      (->> (rp/req :update/page-metadata page)
           (rx/map :payload)
           (rx/map metadata-persisted)))))

(defn persist-metadata
  [id]
  {:pre [(uuid? id)]}
  (PersistMetadata. id))

(deftype PersistPagesMetadata [project-id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [xform (comp
                 (map second)
                 (filter #(= project-id (:project %)))
                 (map :id))]
      (->> (sequence xform (:pages state))
           (rx/from-coll)
           (rx/map persist-metadata)))))

(defn persist-pages-metadata
  [project-id]
  {:pre [(uuid? project-id)]}
  (PersistPagesMetadata. project-id))

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

(deftype ReorderPages [project-id]
  ptk/UpdateEvent
  (update [this state]
    (let [get-order #(get-in % [:metadata :order])
          pages (->> (vals (:pages state))
                     (filter #(= project-id (:project %)))
                     (sort-by get-order)
                     (map :id)
                     (map-indexed vector))]
      (reduce (fn [state [order id]]
                (assoc-in state [:pages id :metadata :order] (* order 10)))
              state
              pages)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (update-project-main-page project-id)
           (persist-pages-metadata project-id))))

(defn reorder-pages
  [id]
  {:pre [(uuid? id)]}
  (ReorderPages. id))

;; --- Update Order
;;
;; A specialized event for update order
;; attribute on the page metadata

(deftype UpdateOrder [id order]
  ptk/UpdateEvent
  (update [this state]
    (assoc-in state [:pages id :metadata :order] order))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [project (get-in state [:pages id :project])]
      (rx/of (reorder-pages project)))))

(defn update-order
  [id order]
  {:pre [(uuid? id) (number? order)]}
  (UpdateOrder. id order))

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
