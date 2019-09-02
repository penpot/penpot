;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  (:require
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [struct.alpha :as st]
   [uxbox.main.repo :as rp]
   [uxbox.util.data :refer [index-by-id]]
   [uxbox.util.spec :as us]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; --- Struct

(st/defs ::inst inst?)
(st/defs ::width (st/&& ::st/number ::st/positive))
(st/defs ::height (st/&& ::st/number ::st/positive))

(st/defs ::metadata
  (st/dict :width ::width
           :height ::height
           :grid-y-axis (st/opt ::st/number)
           :grid-x-axis (st/opt ::st/number)
           :grid-color (st/opt ::st/string)
           :order (st/opt ::st/number)
           :background (st/opt ::st/string)
           :background-opacity (st/opt ::st/number)))

(st/defs ::shapes-list
  (st/coll-of ::st/uuid))

(st/defs ::page-entity
  (st/dict :id ::st/uuid
           :name ::st/string
           :project ::st/uuid
           :created-at ::inst
           :modified-at ::inst
           :user ::st/uuid
           :metadata ::metadata
           :shapes ::shapes-list))

(st/defs ::minimal-shape
  (st/dict :id ::st/uuid
           :type ::st/keyword
           :name ::st/string))

(st/defs ::server-page-data-sapes
  (st/coll-of ::minimal-shape))

(st/defs ::server-page-data
  (st/dict :shapes ::server-page-data-sapes))

(st/defs ::server-page
  (st/dict :id ::st/uuid
           :name ::st/string
           :project ::st/uuid
           :version ::st/integer
           :created-at ::inst
           :modified-at ::inst
           :user ::st/uuid
           :metadata ::metadata
           :data ::server-page-data))

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
  (let [pid (get-in state [:pages id :project])]
    (-> state
        (update-in [:projects pid :pages] #(filterv (partial not= id) %))
        (update :pages dissoc id)
        (update :packed-pages dissoc id)
        (update :shapes (fn [shapes] (->> shapes
                                          (map second)
                                          (filter #(= (:page %) id))
                                          (map :id)
                                          (apply dissoc shapes)))))))

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

(declare rehash-pages)

(st/defs ::page-created
  (st/dict :id ::st/uuid
           :name ::st/string
           :project ::st/uuid
           :metadata ::metadata))

(defn page-created
  [data]
  (assert (st/valid? ::page-created data) "invalid parameters")
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (:project data)]
        (-> state
            (update-in [:projects pid :pages] (fnil conj []) (:id data))
            (unpack-page data)
            (assoc-packed-page data))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (rehash-pages (:project data))))))

;; --- Create Page

(st/defs ::create-page
  (st/dict :name ::st/string
           :project ::st/uuid
           :width ::width
           :height ::height))

(defn create-page
  [{:keys [name project width height layout] :as data}]
  (assert (st/valid? ::create-page data))
  (reify
    ptk/WatchEvent
    (watch [this state s]
      (let [canvas {:id (uuid/random)
                    :name "Canvas 1"
                    :type :canvas
                    :x1 200
                    :y1 200
                    :x2 (+ 200 width)
                    :y2 (+ 200 height)}
            metadata {:width width
                      :height height
                      :order -100}
            params {:name name
                    :project project
                    :data {:shapes [canvas]}
                    :metadata metadata}]
        (->> (rp/req :create/page params)
             (rx/map :payload)
             (rx/map page-created))))))

;; --- Page Persisted

(defn page-persisted
  [data]
  (assert (st/valid? ::server-page data))
  (reify
    cljs.core/IDeref
    (-deref [_] data)

    ptk/EventType
    (type [_] ::page-persisted)

    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [id version]} data]
        (-> state
            (assoc-in [:pages id :version] version)
            (assoc-packed-page data))))))

(defn- page-persisted?
  [event]
  (= (ptk/type event) ::page-persisted))

;; --- Persist Page

(defn persist-page
  ([id] (persist-page id identity))
  ([id on-success]
   (assert (uuid? id))
   (reify
     ptk/EventType
     (type [_] ::persist-page)

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
                  (rx/map page-persisted)))))))))

(defn persist-page?
  [v]
  (= ::persist-page (ptk/type v)))

;; --- Page Metadata Persisted

(deftype MetadataPersisted [id data]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:pages id :version] (:version data))))

(st/defs ::version integer?)
(st/defs ::metadata-persisted-event
  (st/dict :id ::st/uuid
           :version ::version))

(defn metadata-persisted?
  [v]
  (instance? MetadataPersisted v))

(defn metadata-persisted
  [{:keys [id] :as data}]
  {:pre [(st/valid? ::metadata-persisted-event data)]}
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
  {:pre [(uuid? id) (st/valid? ::page-entity data)]}
  (UpdatePage. id data))

;; --- Update Page Metadata

(deftype UpdateMetadata [id metadata]
  IMetadataUpdate
  ptk/UpdateEvent
  (update [this state]
    (assoc-in state [:pages id :metadata] metadata)))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (st/valid? ::metadata metadata)]}
  (UpdateMetadata. id metadata))

;; --- Rehash Pages
;;
;; A post processing event that normalizes the
;; page order numbers after a user sorting
;; operation for a concrete project.

(defn rehash-pages
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [this state]
      (let [page-ids (get-in state [:projects id :pages])]
        (reduce (fn [state [index id]]
                  (assoc-in state [:pages id :metadata :order] index))
                state
                (map-indexed vector page-ids))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [page-ids (get-in state [:projects id :pages])]
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

(st/defs ::persist-page-update-form
  (st/dict :id ::st/uuid
           :name ::st/string
           :width ::width
           :height ::height))

(defn persist-page-update-form
  [{:keys [id name width height] :as data}]
  (assert (st/valid? ::persist-page-update-form data))
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [page (-> (get-in state [:pages id])
                     (assoc-in [:name] name)
                     (assoc-in [:metadata :width] width)
                     (assoc-in [:metadata :height] height))]
        (rx/of (update-page id page))))))


;; --- Delete Page (by id)

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (purge-page state id))

    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/req :delete/page id)
           (rx/map (constantly ::delete-completed))))))

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
