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
            [uxbox.main.repo :as rp]
            [uxbox.main.state :as st]
            [uxbox.util.spec :as us]
            [uxbox.util.rstore :as rs]
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
                   ::layout]))


;; --- Protocols

(defprotocol IPageUpdate
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

(defrecord PagesFetched [pages]
  rs/UpdateEvent
  (-apply-update [_ state]
    (as-> state $
      (reduce assoc-page $ pages)
      (reduce assoc-packed-page $ pages))))

(defn pages-fetched?
  [v]
  (instance? PagesFetched v))

;; --- Fetch Pages (by project id)

(defrecord FetchPages [projectid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (->> (rp/req :fetch/pages-by-project {:project projectid})
         (rx/map (comp ->PagesFetched :payload)))))

(defn fetch-pages
  [projectid]
  (FetchPages. projectid))

;; --- Page Created

(defrecord PageCreated [data]
  rs/UpdateEvent
  (-apply-update [_ state]
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

(defrecord CreatePage [name project width height layout]
  rs/WatchEvent
  (-apply-watch [this state s]
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
  [data]
  {:pre [(us/valid? ::create-page-event data)]}
  (map->CreatePage data))

;; --- Page Persisted

(defrecord PagePersisted [data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-page state data)))

(defn- page-persisted?
  [event]
  (instance? PagePersisted event))

;; TODO: add specs

(defn page-persisted
  [data]
  {:pre [(map? data)]}
  (PagePersisted. data))

;; --- Persist Page

(defrecord PersistPage [id]
  rs/WatchEvent
  (-apply-watch [this state s]
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

(defn watch-page-changes
  "A function that starts watching for `IPageUpdate`
  events emited to the global event stream and just
  reacts emiting an other event in order to perform
  the page persistence.

  The main behavior debounces the posible emmited
  events with 1sec of delay allowing batch updates
  on fastly performed events."
  [id]
  (as-> rs/stream $
    (rx/filter #(satisfies? IPageUpdate %) $)
    (rx/debounce 1000 $)
    (rx/on-next $ #(rs/emit! (persist-page id)))))

;; --- Page Metadata Persisted

(defrecord MetadataPersisted [id data]
  rs/UpdateEvent
  (-apply-update [_ state]
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

(defrecord PersistMetadata [id]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [page (get-in state [:pages id])]
      (->> (rp/req :update/page-metadata page)
           (rx/map :payload)
           (rx/map metadata-persisted)))))

(defn persist-metadata
  [id]
  {:pre [(uuid? id)]}
  (PersistMetadata. id))

;; --- Update Page Options

(defrecord UpdateMetadata [id metadata]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:pages id :metadata] metadata))

  rs/WatchEvent
  (-apply-watch [this state s]
    (rx/of (persist-metadata id))))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (us/valid? ::metadata metadata)]}
  (UpdateMetadata. id metadata))

;; --- Update Page

(defrecord UpdatePage [id name width height layout]
  rs/UpdateEvent
  (-apply-update [this state]
    (println "update-page" this)
    (-> state
        (assoc-in [:pages id :name] name)
        (assoc-in [:pages id :metadata :width] width)
        (assoc-in [:pages id :metadata :height] height)
        (assoc-in [:pages id :metadata :layout] layout)))

  rs/WatchEvent
  (-apply-watch [_ state stream]
    (rx/of (persist-metadata id))))

(s/def ::update-page-event
  (s/keys :req-un [::name ::width ::height ::layout]))

(defn update-page
  [id {:keys [name width height layout] :as data}]
  {:pre [(uuid? id) (us/valid? ::update-page-event data)]}
  (UpdatePage. id name width height layout))

;; --- Delete Page (by id)

(defrecord DeletePage [id callback]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              #(purge-page % id))]
      (->> (rp/req :delete/page id)
           (rx/map on-success)
           (rx/tap callback)
           (rx/filter identity)))))

(defn delete-page
  ([id] (DeletePage. id (constantly nil)))
  ([id callback] (DeletePage. id callback)))
