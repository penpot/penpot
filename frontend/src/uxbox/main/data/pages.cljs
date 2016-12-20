;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.pages
  (:require [cljs.spec :as s]
            [cuerdas.core :as str]
            [beicon.core :as rx]
            [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.store :as st]
            [uxbox.main.repo :as rp]
            [uxbox.util.rlocks :as rlocks]
            [uxbox.util.spec :as us]
            [uxbox.util.router :as r]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.forms :as sc]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (without-keys replace-by-id)]))

;; --- Specs

(s/def ::id uuid?)
(s/def ::name string?)
(s/def ::project uuid?)
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
                   ::background
                   ::background-opacity
                   ::layout]))

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
  (let [page (get-in state [:pages id])
        xf (filter #(= (:page (second %)) id))
        shapes (into {} xf (:shapes state))]
    (-> page
        (assoc-in [:data :shapes] (into [] (:shapes page)))
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

;; --- Pages Fetched

(deftype PagesFetched [pages]
  ptk/UpdateEvent
  (update [_ state]
    (as-> state $
      (reduce assoc-page $ pages)
      (reduce assoc-packed-page $ pages))))

(defn pages-fetched?
  [v]
  (instance? PagesFetched v))

;; --- Fetch Pages (by project id)

(deftype FetchPages [projectid]
  ptk/WatchEvent
  (watch [_ state s]
    (->> (rp/req :fetch/pages-by-project {:project projectid})
         (rx/map (comp ->PagesFetched :payload)))))

(defn fetch-pages
  [projectid]
  (FetchPages. projectid))

;; --- Page Created

(deftype PageCreated [data]
  ptk/UpdateEvent
  (update [_ state]
    (-> state
        (assoc-page data)
        (assoc-packed-page data))))

(s/def ::page-created-event
  (s/keys :req-un [::id ::name ::project ::metadata]))

(defn page-created
  [data]
  {:pre [(us/valid? ::page-created-event data)]}
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
                             :layout layout}}]
      (->> (rp/req :create/page params)
           (rx/map :payload)
           (rx/map page-created)))))

(s/def ::create-page-event
  (s/keys :req-un [::name ::project ::width ::height ::layout]))

(defn create-page
  [{:keys [name project width height layout] :as data}]
  {:pre [(us/valid? ::create-page-event data)]}
  (->CreatePage name project width height layout))

;; --- Page Persisted

(deftype PagePersisted [data]
  ptk/UpdateEvent
  (update [_ state]
    ;; TODO: update only the version instead of complete unpacking
    ;; this will improve the application responsiveness when multiple
    ;; updates are performed
    (assoc-page state data)))

(defn- page-persisted?
  [event]
  (instance? PagePersisted event))

;; TODO: add page spec

(defn page-persisted
  [data]
  {:pre [(map? data)]}
  (PagePersisted. data))

;; --- Persist Page

(deftype PersistPage [id]
  ptk/WatchEvent
  (watch [this state s]
    (let [page (get-in state [:pages id])]
      (if (:history page)
        (rx/empty)
        (let [page (pack-page state id)]
          (->> (rp/req :update/page page)
               (rx/map :payload)
               (rx/map page-persisted)))))))

(defn persist-page?
  [v]
  (instance? PersistPage v))

(defn persist-page
  [id]
  (PersistPage. id))

;; --- Page Metadata Persisted

(deftype MetadataPersisted [id data]
  ptk/UpdateEvent
  (update [_ state]
    ;; TODO: page-data update
    (assoc-in state [:pages id :version] (:version data))))

(s/def ::metadata-persisted-event
  (s/keys :req-un [::id ::version]))

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

;; --- Update Page Options

(deftype UpdateMetadata [id metadata]
  IMetadataUpdate
  ptk/UpdateEvent
  (update [this state]
    (assoc-in state [:pages id :metadata] metadata)))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (us/valid? ::metadata metadata)]}
  (UpdateMetadata. id metadata))

;; --- Update Page

(deftype UpdatePage [id name width height layout]
  ptk/UpdateEvent
  (update [this state]
    (-> state
        (assoc-in [:pages id :name] name)
        (assoc-in [:pages id :metadata :width] width)
        (assoc-in [:pages id :metadata :height] height)
        (assoc-in [:pages id :metadata :layout] layout)))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (persist-metadata id))))

(s/def ::update-page-event
  (s/keys :req-un [::name ::width ::height ::layout]))

(defn update-page
  [id {:keys [name width height layout] :as data}]
  {:pre [(uuid? id) (us/valid? ::update-page-event data)]}
  (UpdatePage. id name width height layout))

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
            (rx/take-until stopper)
            (rx/filter #(not= @rlocks/lock :shape/resize))
            (rx/filter #(satisfies? IPageUpdate %))
            (rx/debounce 1000)
            (rx/map #(persist-page id)))
       (->> stream
            (rx/take-until stopper)
            (rx/filter #(satisfies? IMetadataUpdate %))
            (rx/debounce 1000)
            (rx/map #(persist-metadata id)))))))

(defn watch-page-changes
  [id]
  (WatchPageChanges. id))
