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
   [uxbox.main.repo.core :as rp]
   [uxbox.util.data :refer [index-by-id concatv]]
   [uxbox.util.spec :as us]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; --- Struct

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::inst ::us/inst)
(s/def ::type ::us/keyword)
(s/def ::project-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/number)
(s/def ::width (s/and ::us/number ::us/positive))
(s/def ::height (s/and ::us/number ::us/positive))
(s/def ::grid-x-axis ::us/number)
(s/def ::grid-y-axis ::us/number)
(s/def ::grid-color ::us/string)
(s/def ::ordering ::us/number)
(s/def ::background ::us/string)
(s/def ::background-opacity ::us/number)
(s/def ::user ::us/uuid)

(s/def ::metadata
  (s/keys :opt-un [::grid-y-axis
                   ::grid-x-axis
                   ::grid-color
                   ::background
                   ::background-opacity]))

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shapes (s/every ::us/uuid :kind vector? :into []))
(s/def ::canvas (s/every ::us/uuid :kind vector? :into []))

(s/def ::shapes-by-id
  (s/map-of ::us/uuid ::minimal-shape))

(s/def ::data
  (s/keys :req-un [::shapes ::canvas ::shapes-by-id]))

(s/def ::page-entity
  (s/keys :req-un [::id
                   ::name
                   ::project-id
                   ::created-at
                   ::modified-at
                   ::user-id
                   ::ordering
                   ::metadata
                   ::data]))

;; --- Protocols

(defprotocol IPageUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

(defprotocol IMetadataUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

(defn page-update?
  [o]
  (or (satisfies? IPageUpdate o)
      (satisfies? IMetadataUpdate o)
      (= ::page-update o)))

;; --- Helpers

;; (defn pack-page
;;   "Return a packed version of page object ready
;;   for send to remore storage service."
;;   [state id]
;;   (letfn [(pack-shapes [ids]
;;             (mapv #(get-in state [:shapes %]) ids))]
;;     (let [page (get-in state [:pages id])
;;           data {:shapes (pack-shapes (concatv (:canvas page)
;;                                               (:shapes page)))}]
;;       (-> page
;;           (assoc :data data)
;;           (dissoc :shapes)))))

(defn unpack-page
  [state {:keys [id data metadata] :as page}]
  (-> state
      (update :pages assoc id (dissoc page :data))
      (update :pages-data assoc id data)))

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (if-let [project-id (get-in state [:pages id :project-id])]
    (-> state
        (update-in [:projects project-id :pages] #(filterv (partial not= id) %))
        (update :pages dissoc id)
        (update :pages-data dissoc id))
    state))

;; --- Pages Fetched

(defn pages-fetched
  [id pages]
  (s/assert ::us/uuid id)
  (s/assert ::us/coll pages)
  (ptk/reify ::pages-fetched
    IDeref
    (-deref [_] (list id pages))

    ptk/UpdateEvent
    (update [_ state]
      (reduce unpack-page state pages))))

(defn pages-fetched?
  [v]
  (= ::pages-fetched (ptk/type v)))

;; --- Fetch Pages (by project id)

(defn fetch-pages
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :pages-by-project {:project-id id})
           (rx/map #(pages-fetched id %))))))

;; --- Page Fetched

(defn page-fetched
  [data]
  (s/assert ::page-entity data)
  (ptk/reify ::page-fetched
    IDeref
    (-deref [_] data)

    ptk/UpdateEvent
    (update [_ state]
      (unpack-page state data))))

(defn page-fetched?
  [v]
  (= ::page-fetched (ptk/type v)))

;; --- Fetch Pages (by project id)

(defn fetch-page
  "Fetch page by id."
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :page {:id id})
           (rx/map page-fetched)))))

;; --- Page Created

(defn page-created
  [{:keys [id project-id] :as page}]
  (s/assert ::page-entity page)
  (ptk/reify ::page-created
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (let [data (:data page)
            page (dissoc page :data)]
        (-> state
            (update-in [:projects project-id :pages] (fnil conj []) (:id page))
            (update :pages assoc id page)
            (update :pages-data assoc id data))))))

(defn page-created?
  [v]
  (= ::page-created (ptk/type v)))

;; --- Create Page Form

(s/def ::create-page
  (s/keys :req-un [::name ::project-id]))

(defn create-page
  [{:keys [project-id name] :as data}]
  (s/assert ::create-page data)
  (ptk/reify ::create-page
    ptk/WatchEvent
    (watch [this state s]
      (let [ordering (count (get-in state [:projects project-id :pages]))
            params {:name name
                    :project-id project-id
                    :ordering ordering
                    :data {:shapes []
                           :canvas []
                           :shapes-by-id {}}
                    :metadata {}}]
        (->> (rp/mutation :create-page params)
             (rx/map page-created))))))

;; --- Update Page Form

(declare page-renamed)

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [{:keys [id name] :as data}]
  (s/assert ::rename-page data)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:pages id] assoc :name name))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-page params)
             (rx/map #(ptk/data-event ::page-renamed data)))))))

;; --- Page Metadata Persisted

(s/def ::metadata-persisted-params
  (s/keys :req-un [::id ::version]))

(defn metadata-persisted
  [{:keys [id] :as data}]
  (s/assert ::metadata-persisted-params data)
  (ptk/reify ::metadata-persisted
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
  (s/assert ::us/uuid id)
  (ptk/reify ::persist-metadata
    ptk/WatchEvent
    (watch [_ state stream]
      #_(let [page (get-in state [:pages id])]
        (->> (rp/req :update/page-metadata page)
             (rx/map :payload)
             (rx/map metadata-persisted))))))

;; --- Update Page

(defn update-page-attrs
  [{:keys [id] :as data}]
  (s/assert ::page-entity data)
  (ptk/reify
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
      (->> (rp/mutation :delete-page  {:id id})
           (rx/map (constantly ::delete-completed))))))
