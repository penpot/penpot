;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  "Page related events (for workspace mainly)."
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [uxbox.main.repo.core :as rp]
   [uxbox.main.data.projects :as dp]
   [uxbox.util.data :refer [index-by-id concatv]]
   [uxbox.util.spec :as us]
   [uxbox.util.timers :as ts]
   [uxbox.util.uuid :as uuid]))

;; --- Struct

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::inst ::us/inst)
(s/def ::type ::us/keyword)
(s/def ::file-id ::us/uuid)
(s/def ::created-at ::us/inst)
(s/def ::modified-at ::us/inst)
(s/def ::version ::us/number)
(s/def ::width (s/and ::us/number ::us/positive))
(s/def ::height (s/and ::us/number ::us/positive))

(s/def ::grid-x-axis ::us/number)
(s/def ::grid-y-axis ::us/number)
(s/def ::grid-color ::us/string)
(s/def ::background ::us/string)
(s/def ::background-opacity ::us/number)

(s/def ::ordering ::us/number)
(s/def ::user ::us/uuid)

(s/def ::metadata
  (s/keys :opt-un [::grid-y-axis
                   ::grid-x-axis
                   ::grid-color
                   ::background
                   ::background-opacity]))

;; TODO: start using uxbox.common.pagedata/data spec ...

(s/def ::minimal-shape
  (s/keys :req-un [::type ::name]
          :opt-un [::id]))

(s/def ::shapes (s/coll-of ::us/uuid :kind vector?))
(s/def ::canvas (s/coll-of ::us/uuid :kind vector?))

(s/def ::shapes-by-id
  (s/map-of ::us/uuid ::minimal-shape))

(s/def ::data
  (s/keys :req-un [::shapes ::canvas ::shapes-by-id]))

(s/def ::page
  (s/keys :req-un [::id
                   ::name
                   ::file-id
                   ::created-at
                   ::modified-at
                   ::user-id
                   ::ordering
                   ::metadata
                   ::data]))

(s/def ::pages
  (s/every ::page :kind vector?))

;; --- Protocols

(defprotocol IPageDataUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

(defn page-update?
  [o]
  (or (satisfies? IPageDataUpdate o)
      (= ::page-data-update o)))

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
  (if-let [file-id (get-in state [:pages id :file-id])]
    (-> state
        (update-in [:files file-id :pages] #(filterv (partial not= id) %))
        (update-in [:workspace-file :pages] #(filterv (partial not= id) %))
        (update :pages dissoc id)
        (update :pages-data dissoc id))
    state))

;; --- Fetch Pages (by File ID)

(declare pages-fetched)

(defn fetch-pages
  [file-id]
  (s/assert ::us/uuid file-id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :project-pages {:file-id file-id})
           (rx/map pages-fetched)))))

;; --- Pages Fetched

(defn pages-fetched
  [pages]
  (s/assert ::pages pages)
  (ptk/reify ::pages-fetched
    IDeref
    (-deref [_] pages)

    ptk/UpdateEvent
    (update [_ state]
      (reduce unpack-page state pages))))

;; --- Fetch Page (By ID)

(declare page-fetched)

(defn fetch-page
  "Fetch page by id."
  [id]
  (s/assert ::us/uuid id)
  (reify
    ptk/WatchEvent
    (watch [_ state s]
      (->> (rp/query :project-page {:id id})
           (rx/map page-fetched)))))

;; --- Page Fetched

(defn page-fetched
  [data]
  (s/assert ::page data)
  (ptk/reify ::page-fetched
    IDeref
    (-deref [_] data)

    ptk/UpdateEvent
    (update [_ state]
      (unpack-page state data))))

;; --- Create Page

(declare page-created)

(s/def ::create-page
  (s/keys :req-un [::name ::file-id]))

(defn create-page
  [{:keys [file-id name] :as data}]
  (s/assert ::create-page data)
  (ptk/reify ::create-page
    ptk/WatchEvent
    (watch [this state s]
      (let [ordering (count (get-in state [:files file-id :pages]))
            params {:name name
                    :file-id file-id
                    :ordering ordering
                    :data {:shapes []
                           :canvas []
                           :shapes-by-id {}}
                    :metadata {}}]
        (->> (rp/mutation :create-project-page params)
             (rx/map page-created))))))

;; --- Page Created

(defn page-created
  [{:keys [id file-id] :as page}]
  (s/assert ::page page)
  (ptk/reify ::page-created
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (let [data (:data page)
            page (dissoc page :data)]
        (-> state
            (update-in [:workspace-file :pages] (fnil conj []) id)
            (update :pages assoc id page)
            (update :pages-data assoc id data))))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (uxbox.main.data.projects/fetch-file file-id)))))

;; --- Rename Page

(s/def ::rename-page
  (s/keys :req-un [::id ::name]))

(defn rename-page
  [{:keys [id name] :as data}]
  (s/assert ::rename-page data)
  (ptk/reify ::rename-page
    ptk/UpdateEvent
    (update [_ state]
      (let [pid (get-in state [:workspace-page :id])
            state (assoc-in state [:pages id :name] name)]
        (cond-> state
          (= pid id) (assoc-in [:workspace-page :name] name))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [params {:id id :name name}]
        (->> (rp/mutation :rename-page params)
             (rx/map #(ptk/data-event ::page-renamed data)))))))

;; --- Delete Page (by ID)

(defn delete-page
  [id]
  {:pre [(uuid? id)]}
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (purge-page state id))

    ptk/WatchEvent
    (watch [_ state s]
      (let [page (:workspace-page state)]
        (rx/merge
         (->> (rp/mutation :delete-project-page  {:id id})
              (rx/flat-map (fn [_]
                             (if (= id (:id page))
                               (rx/of (dp/go-to (:file-id page)))
                               (rx/empty))))))))))

;; --- Persist Page

(declare page-persisted)

(def persist-current-page
  (ptk/reify ::persist-page
    ptk/WatchEvent
    (watch [this state s]
      (let [local (:workspace-local state)
            page (:workspace-page state)
            data (:workspace-data state)]
        (if (:history local)
          (rx/empty)
          (let [page (assoc page :data data)]
            (->> (rp/mutation :update-project-page-data page)
                 (rx/map (fn [res] (merge page res)))
                 (rx/map page-persisted)
                 (rx/catch (fn [err] (rx/of ::page-persist-error))))))))))

;; --- Page Persisted

(defn page-persisted
  [{:keys [id] :as page}]
  (s/assert ::page page)
  (ptk/reify ::page-persisted
    cljs.core/IDeref
    (-deref [_] page)

    ptk/UpdateEvent
    (update [_ state]
      (let [data (:data page)
            page (dissoc page :data)]
        (-> state
            (assoc :workspace-data data)
            (assoc :workspace-page page)
            (update :pages assoc id page)
            (update :pages-data assoc id data))))))

;; --- Update Page

;; TODO: deprecated, need refactor (this is used on page options)
(defn update-page-attrs
  [{:keys [id] :as data}]
  (s/assert ::page data)
  (ptk/reify ::update-page-attrs
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-page merge (dissoc data :id :version)))))

;; --- Update Page Metadata

;; TODO: deprecated, need refactor (this is used on page options)
(defn update-metadata
  [id metadata]
  (s/assert ::id id)
  (s/assert ::metadata metadata)
  (reify
    ptk/UpdateEvent
    (update [this state]
      (assoc-in state [:pages id :metadata] metadata))))
