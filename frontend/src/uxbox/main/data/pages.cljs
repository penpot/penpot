;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.main.repo :as rp]
   [uxbox.util.data :refer [index-by-id]]
   [uxbox.util.spec :as us]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; --- Struct

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::inst ::us/inst)
(s/def ::type ::us/keyword)
(s/def ::project ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/number)
(s/def ::width (s/and ::us/number ::us/positive))
(s/def ::height (s/and ::us/number ::us/positive))
(s/def ::grid-x-axis ::us/number)
(s/def ::grid-y-axis ::us/number)
(s/def ::grid-color ::us/string)
(s/def ::order ::us/number)
(s/def ::background ::us/string)
(s/def ::background-opacity ::us/number)
(s/def ::user ::us/uuid)

(s/def ::metadata
  (s/keys :req-un [::width ::height]
          :opt-un [::grid-y-axis
                   ::grid-x-axis
                   ::grid-color
                   ::order
                   ::background
                   ::background-opacity]))

(s/def ::shapes
  (s/coll-of ::us/uuid :kind vector? :into []))

(s/def ::page-entity
  (s/keys :req-un [::id
                   ::name
                   ::project
                   ::created-at
                   ::modified-at
                   ::user
                   ::metadata
                   ::shapes]))

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def :uxbox.backend/shapes
  (s/coll-of ::minimal-shape :kind vector?))

(s/def :uxbox.backend/data
  (s/keys :req-un [:uxbox.backend/shapes]))

(s/def ::server-page
  (s/keys :req-un [::id ::name
                   ::project
                   ::version
                   ::created-at
                   ::modified-at
                   ::user
                   ::metadata
                   :uxbox.backend/data]))

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

(defn pages-fetched
  [id pages]
  (s/assert ::us/uuid id)
  (s/assert ::us/coll pages)
  (reify
    IDeref
    (-deref [_] (list id pages))

    ptk/EventType
    (type [_] ::page-fetched)

    ptk/UpdateEvent
    (update [_ state]
      (let [get-order #(get-in % [:metadata :order])
            pages (sort-by get-order pages)
            page-ids (into [] (map :id) pages)]
        (as-> state $
          (assoc-in $ [:projects id :pages] page-ids)
          (reduce unpack-page $ pages)
          (reduce assoc-packed-page $ pages))))))

(defn pages-fetched?
  [v]
  (= ::page-fetched (ptk/type v)))

;; --- Fetch Pages (by project id)

(defn fetch-pages
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/req :fetch/pages-by-project {:project id})
           (rx/map :payload)
           (rx/map #(pages-fetched id %))))))

;; --- Page Created

(declare rehash-pages)

(s/def ::page-created-params
  (s/keys :req-un [::id ::name ::project ::metadata]))

(defn page-created
  [data]
  (s/assert ::page-created-params data)
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

;; --- Create Page Form

(s/def ::form-created-page-params
  (s/keys :req-un [::name ::project ::width ::height]))

(defn form->create-page
  [{:keys [name project width height layout] :as data}]
  (s/assert ::form-created-page-params data)
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

;; --- Update Page Form

(s/def ::form-update-page-params
  (s/keys :req-un [::id ::name ::width ::height]))

(defn form->update-page
  [{:keys [id name width height] :as data}]
  (s/assert ::form-update-page-params data)
  (reify
    IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:pages id]
                 (fn [page]
                   (-> (assoc page :name name)
                       (assoc-in [:name] name)
                       (assoc-in [:metadata :width] width)
                       (assoc-in [:metadata :height] height)))))))

;; --- Page Persisted

(defn page-persisted
  [data]
  (s/assert ::server-page data)
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

(s/def ::metadata-persisted-params
  (s/keys :req-un [::id ::version]))

(defn metadata-persisted
  [{:keys [id] :as data}]
  (s/assert ::metadata-persisted-params data)
  (reify
    ptk/EventType
    (type [_] ::metadata-persisted)

    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:pages id :version] (:version data)))))

(defn metadata-persisted?
  [v]
  (= ::metadata-persisted (ptk/type v)))


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

(defn update-page-attrs
  [{:keys [id] :as data}]
  (s/assert ::page-entity data)
  (reify
    IPageUpdate
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:pages id] merge (dissoc data :id :version)))))

;; --- Update Page Metadata

(defn update-metadata
  [id metadata]
  (s/assert ::id id)
  (s/assert ::metadata metadata)
  (reify
    IMetadataUpdate
    ptk/UpdateEvent
    (update [this state]
      (assoc-in state [:pages id :metadata] metadata))))


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

(defn watch-page-changes
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state stream]
      (let [stopper (rx/filter #(= % ::stop-page-watcher) stream)]
        (->> (rx/merge
              (->> stream
                   (rx/filter #(or (satisfies? IPageUpdate %)
                                   (= ::page-update %)))
                   (rx/debounce 1000)
                   (rx/mapcat #(rx/merge (rx/of (persist-page id))
                                         (->> (rx/filter page-persisted? stream)
                                              (rx/take 1)
                                              (rx/ignore)))))
              (->> stream
                   (rx/filter #(satisfies? IMetadataUpdate %))
                   (rx/debounce 1000)
                   (rx/mapcat #(rx/merge (rx/of (persist-metadata id))
                                         (->> (rx/filter metadata-persisted? stream)
                                              (rx/take 1)
                                              (rx/ignore))))))
             (rx/take-until stopper))))))

