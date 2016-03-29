;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.pages
  (:require [cuerdas.core :as str]
            [promesa.core :as p]
            [beicon.core :as rx]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.repo :as rp]
            [uxbox.locales :refer (tr)]
            [uxbox.schema :as sc]
            [uxbox.state :as st]
            [uxbox.state.project :as stpr]
            [uxbox.ui.messages :as uum]
            [uxbox.util.datetime :as dt]
            [uxbox.util.data :refer (without-keys)]))

(defprotocol IPageUpdate
  "A marker protocol for mark events that alters the
  page and is subject to perform a backend synchronization.")

;; --- Pages Fetched

(defrecord PagesFetched [pages]
  rs/UpdateEvent
  (-apply-update [_ state]
    (as-> state $
      (reduce stpr/unpack-page $ pages)
      (reduce stpr/assoc-page $ pages))))

(defn pages-fetched?
  [v]
  (instance? PagesFetched v))

;; --- Fetch Pages (by project id)

(defrecord FetchPages [projectid]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-loaded [{pages :payload}]
              (->PagesFetched pages))
            (on-error [err]
              (js/console.error err)
              (rx/empty))]
      (->> (rp/do :fetch/pages-by-project {:project projectid})
           (rx/map on-loaded)
           (rx/catch on-error)))))

(defn fetch-pages
  [projectid]
  (FetchPages. projectid))

;; --- Create Page

(defrecord CreatePage [name width height project layout]
  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-created [{page :payload}]
              (rx/of
               #(stpr/unpack-page % page)
               #(stpr/assoc-page % page)))
            (on-failed [page]
              (uum/error (tr "errors.auth"))
              (rx/empty))]
      (let [params (-> (into {} this)
                       (assoc :data {}))]
        (->> (rp/do :create/page params)
             (rx/mapcat on-created)
             (rx/catch on-failed))))))

(def ^:static +create-page-schema+
  {:name [sc/required sc/string]
   :layout [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :project [sc/required sc/uuid]})

(defn create-page
  [data]
  (sc/validate! +create-page-schema+ data)
  (map->CreatePage data))

;; --- Sync Page

(defrecord SyncPage [id]
  rs/WatchEvent
  (-apply-watch [this state s]
    (println "SyncPage")
    (letfn [(on-success [{page :payload}]
              (rx/of
               #(assoc-in % [:pages-by-id id :version] (:version page))
               #(stpr/assoc-page % page)))
            (on-failure [e]
              (uum/error (tr "errors.page-update"))
              (rx/empty))]
      (let [page (stpr/pack-page state id)]
        (->> (rp/do :update/page page)
             (rx/mapcat on-success)
             (rx/catch on-failure))))))

(defn sync-page
  [id]
  (SyncPage. id))

;; --- Update Page

(declare fetch-page-history)
(declare fetch-pinned-page-history)

(defrecord UpdatePage [id]
  rs/WatchEvent
  (-apply-watch [this state s]
    (println "UpdatePage")
    (let [page (get-in state [:pages-by-id id])]
      (if (:history page)
        (rx/empty)
        (rx/of (sync-page id)
               (fetch-page-history id)
               (fetch-pinned-page-history id))))))

(defn update-page
  [id]
  (UpdatePage. id))

(defn watch-page-changes
  [id]
  (letfn [(on-value []
            (rs/emit! (update-page id)))]
    (as-> rs/stream $
      (rx/filter #(satisfies? IPageUpdate %) $)
      (rx/debounce 2000 $)
      (rx/on-next $ on-value))))

;; --- Update Page Metadata

;; This is a simplified version of `UpdatePage` event
;; that does not sends the heavyweiht `:data` attribute
;; and only serves for update other page data.

(defrecord UpdatePageMetadata [id name width height layout]
  rs/UpdateEvent
  (-apply-update [_ state]
    (letfn [(updater [page]
              (merge page
                     (when width {:width width})
                     (when height {:height height})
                     (when name {:name name})))]
      (update-in state [:pages-by-id id] updater)))

  rs/WatchEvent
  (-apply-watch [this state s]
    (letfn [(on-success [{page :payload}]
              #(assoc-in % [:pages-by-id id :version] (:version page)))
            (on-failure [e]
              (uum/error (tr "errors.page-update"))
              (rx/empty))]
      (->> (rp/do :update/page-metadata (into {} this))
           (rx/map on-success)
           (rx/catch on-failure)))))

(def ^:const +update-page-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn update-page-metadata
  [data]
  (sc/validate! +update-page-schema+ data)
  (map->UpdatePageMetadata (dissoc data :data)))

;; --- Delete Page (by id)

(defrecord DeletePage [id callback]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stpr/purge-page % id)))
            (on-failure [e]
              (uum/error (tr "errors.delete-page"))
              (rx/empty))]
      (->> (rp/do :delete/page id)
           (rx/map on-success)
           (rx/tap callback)
           (rx/filter identity)
           (rx/catch on-failure)))))

(defn delete-page
  ([id] (DeletePage. id (constantly nil)))
  ([id callback] (DeletePage. id callback)))

;; --- Pinned Page History Fetched

(defrecord PinnedPageHistoryFetched [history]
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:workspace :history :pinned-items] history)))

;; --- Fetch Pinned Page History

(defrecord FetchPinnedPageHistory [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [{history :payload}]
              (->PinnedPageHistoryFetched history))
            (on-failure [e]
              (uum/error (tr "errors.fetch-page-history"))
              (rx/empty))]
      (let [params {:page id :pinned true}]
        (->> (rp/do :fetch/page-history params)
             (rx/map on-success)
             (rx/catch on-failure))))))

(defn fetch-pinned-page-history
  [id]
  (->FetchPinnedPageHistory id))

;; --- Page History Fetched

(defrecord PageHistoryFetched [history append?]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [items (into [] history)
          minv (apply min (map :version history))
          state (assoc-in state [:workspace :history :min-version] minv)]
      (if-not append?
        (assoc-in state [:workspace :history :items] items)
        (update-in state [:workspace :history :items] #(reduce conj % items))))))

;; --- Fetch Page History

(defrecord FetchPageHistory [id since max]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [{history :payload}]
              (->PageHistoryFetched history (not (nil? since))))
            (on-failure [e]
              (uum/error (tr "errors.fetch-page-history"))
              (rx/empty))]
      (let [params {:page id  :max (or max 15)}]
        (->> (rp/do :fetch/page-history params)
             (rx/map on-success)
             (rx/catch on-failure))))))

(defn fetch-page-history
  ([id]
   (fetch-page-history id nil))
  ([id params]
   (map->FetchPageHistory (assoc params :id id))))

;; --- Clean Page History

(defrecord CleanPageHistory []
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (assoc-in [:workspace :history :items] nil)
        (assoc-in [:workspace :history :selected] nil))))

(defn clean-page-history
  []
  (CleanPageHistory.))

;; --- Select Page History

(defrecord SelectPageHistory [id history]
  rs/UpdateEvent
  (-apply-update [_ state]
    (if (nil? history)
      (let [packed (get-in state [:pagedata-by-id id])]
        (-> state
            (stpr/unpack-page packed)
            (assoc-in [:workspace :history :selected] nil)))
      (let [page (get-in state [:pages-by-id id])
            page' (assoc page
                         :history true
                         :data (:data history))]
        (-> state
            (stpr/unpack-page page')
            (assoc-in [:workspace :history :selected] (:id history)))))))

(defn select-page-history
  [id history]
  (SelectPageHistory. id history))

;; --- Apply selected history

(defrecord ApplySelectedHistory [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (println "ApplySelectedHistory" id)
    (-> state
        (update-in [:pages-by-id id] dissoc :history)
        (assoc-in [:workspace :history :selected] nil)))

  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (update-page id))))

(defn apply-selected-history
  [id]
  (ApplySelectedHistory. id))

;; --- Discard Selected History

(defrecord DiscardSelectedHistory [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [packed (get-in state [:pagedata-by-id id])]
      (-> state
          (stpr/unpack-page packed)
          (assoc-in [:workspace :history :selected] nil)))))

(defn discard-selected-history
  [id]
  (DiscardSelectedHistory. id))
