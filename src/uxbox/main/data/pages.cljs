;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.data.pages
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as r]
            [uxbox.main.repo :as rp]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.schema :as sc]
            [uxbox.main.state :as st]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (without-keys replace-by-id)]))

(defprotocol IPageUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

;; --- Page Pack/Unpack

(defn dissoc-page-shapes
  [state id]
  (let [shapes (get-in state [:shapes-by-id])]
    (assoc state :shapes-by-id (reduce-kv (fn [acc k v]
                                            (if (= (:page v) id)
                                              (dissoc acc k)
                                              acc))
                                          shapes
                                          shapes))))

(defn pack-page
  "Return a packed version of page object ready
  for send to remore storage service."
  [state id]
  (let [page (get-in state [:pages-by-id id])
        xf (filter #(= (:page (second %)) id))
        shapes (into {} xf (:shapes-by-id state))]
    (-> page
        (assoc-in [:data :shapes] (into [] (:shapes page)))
        (assoc-in [:data :shapes-by-id] shapes)
        (update-in [:data] dissoc :items)
        (dissoc :shapes))))

(defn unpack-page
  "Unpacks packed page object and assocs it to the
  provided state."
  [state page]
  (let [data (:data page)
        shapes (:shapes data)
        shapes-by-id (:shapes-by-id data)
        page (-> (dissoc page :data)
                 (assoc :shapes shapes))]
    (-> state
        (update :shapes-by-id merge shapes-by-id)
        (update :pages-by-id assoc (:id page) page))))

(defn purge-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update :pages-by-id dissoc id)
      (update :pagedata-by-id dissoc id)
      (dissoc-page-shapes id)))

(defn assoc-page
  [state {:keys [id] :as page}]
  (assoc-in state [:pagedata-by-id id] page))

(defn dissoc-page
  [state id]
  (update state :pagedata-by-id dissoc id))

;; --- Pages Fetched

(defrecord PagesFetched [pages]
  rs/UpdateEvent
  (-apply-update [_ state]
    (as-> state $
      (reduce unpack-page $ pages)
      (reduce assoc-page $ pages))))

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

;; --- Create Page

(defrecord CreatePage [name project metadata]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-created [{page :payload}]
              (rx/of
               #(unpack-page % page)
               #(assoc-page % page)))]
      (let [params {:name name
                    :project project
                    :data {}
                    :metadata metadata}]
        (->> (rp/req :create/page params)
             (rx/mapcat on-created))))))

(def ^:private create-page-schema
  {:name [sc/required sc/string]
   :metadata [sc/required]
   :project [sc/required sc/uuid]})

(defn create-page
  [data]
  (-> (sc/validate! data create-page-schema)
      (map->CreatePage)))

;; --- Page Synced

(defrecord PageSynced [page]
  rs/UpdateEvent
  (-apply-update [this state]
    (-> state
        (assoc-in [:pages-by-id (:id page) :version] (:version page))
        (assoc-page page))))

(defn- page-synced?
  [event]
  (instance? PageSynced event))

;; --- Sync Page

(defrecord SyncPage [id]
  rs/WatchEvent
  (-apply-watch [this state s]
    (let [page (pack-page state id)]
      (->> (rp/req :update/page page)
           (rx/map (comp ->PageSynced :payload))))))

(defn sync-page
  [id]
  (SyncPage. id))

;; --- Update Page

(defrecord UpdatePage [id]
  rs/WatchEvent
  (-apply-watch [this state s]
    (let [page (get-in state [:pages-by-id id])]
      (if (:history page)
        (rx/empty)
        (rx/of (sync-page id))))))

(defn update-page?
  [v]
  (instance? UpdatePage v))

(defn update-page
  [id]
  (UpdatePage. id))

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
    (rx/on-next $ #(rs/emit! (update-page id)))))

;; --- Update Page Metadata

;; This is a simplified version of `UpdatePage` event
;; that does not sends the heavyweiht `:data` attribute
;; and only serves for update other page data.

;; TODO: sync also with the pagedata-by-id index.

(defrecord UpdatePageMetadata [id name width height layout options]
  rs/UpdateEvent
  (-apply-update [_ state]
    (letfn [(updater [page]
              (merge page
                     (when options {:options options})
                     (when width {:width width})
                     (when height {:height height})
                     (when name {:name name})))]
      (update-in state [:pages-by-id id] updater)))

  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{page :payload}]
              #(assoc-in % [:pages-by-id id :version] (:version page)))]
      (->> (rp/req :update/page-metadata (into {} this))
           (rx/map on-success)))))

(def ^:private update-page-schema
  {:id [sc/required]
   :project [sc/required]
   :version [sc/required]
   :name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn update-page-metadata
  [data]
  (-> (sc/validate! data update-page-schema {:strip false})
      (dissoc data :data)
      (map->UpdatePageMetadata)))

;; --- Update Page Options

(defrecord UpdatePageOptions [id options]
  rs/WatchEvent
  (-apply-watch [this state s]
    (let [page (get-in state [:pages-by-id id])
          page (assoc page :options options)]
      (rx/of (map->UpdatePageMetadata page)))))

(defn update-page-options
  [id options]
  (UpdatePageOptions. id options))

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
