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

;; --- Pages Fetched

(defrecord PagesFetched [pages]
  rs/UpdateEvent
  (-apply-update [_ state]
    (reduce stpr/unpack-page state pages)))

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
              #(stpr/assoc-page % page))
            (on-failed [page]
              (uum/error (tr "errors.auth"))
              (rx/empty))]
      (let [params (-> (into {} this)
                       (assoc :data {}))]
        (->> (rp/do :create/page params)
             (rx/map on-created)
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

;; --- Update Page

(defrecord UpdatePage [id name width height layout data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (letfn [(updater [page]
              (merge page
                     (when data {:data data})
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
      (->> (rp/do :update/page (into {} this))
           (rx/map on-success)
           (rx/catch on-failure)))))

(def ^:static +update-page-schema+
  {:name [sc/required sc/string]
   :width [sc/required sc/integer]
   :height [sc/required sc/integer]
   :layout [sc/required sc/string]})

(defn update-page
  [data]
  (sc/validate! +update-page-schema+ data)
  (map->UpdatePage data))

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
              (println "on-success")
              #(assoc-in % [:pages-by-id id :version] (:version page)))
            (on-failure [e]
              (println "on-failure" e)
              (uum/error (tr "errors.page-update"))
              (rx/empty))]
      (->> (rp/do :update/page-metadata (into {} this))
           (rx/map on-success)
           (rx/catch on-failure)))))

(defn update-page-metadata
  [data]
  (sc/validate! +update-page-schema+ data)
  (map->UpdatePageMetadata (dissoc data :data)))


;; --- Delete Page (by id)

(defrecord DeletePage [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (letfn [(on-success [_]
              (rs/swap #(stpr/dissoc-page % id)))
            (on-failure [e]
              (uum/error (tr "errors.delete-page"))
              (rx/empty))]
      (->> (rp/do :delete/page id)
           (rx/map on-success)
           (rx/catch on-failure)))))

(defn delete-page
  [id]
  (DeletePage. id))

;; --- Page History Fetched

(defrecord PageHistoryFetched [history]
  rs/UpdateEvent
  (-apply-update [_ state]
    (-> state
        (assoc-in [:workspace :history :items] history))))
        ;; (assoc-in [:workspace :history :selected] nil))))

;; --- Fetch Page History

(defrecord FetchPageHistory [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (println "FetchPageHistory" id)
    (letfn [(on-success [{history :payload}]
              (->PageHistoryFetched history))
            (on-failure [e]
              (uum/error (tr "errors.fetch-page-history"))
              (rx/empty))]
      (->> (rp/do :fetch/page-history {:page id :max 15})
           (rx/map on-success)
           (rx/catch on-failure))))

  rs/EffectEvent
  (-apply-effect [_ state]
    ))

(defn fetch-page-history
  [id]
  (FetchPageHistory. id))

;; --- Clean Page History

(defrecord CleanPageHistory []
  rs/UpdateEvent
  (-apply-update [_ state]
    (println "CleanPageHistory")
    (-> state
        (assoc-in [:workspace :history :items] nil)
        (assoc-in [:workspace :history :selected] nil))))

(defn clean-page-history
  []
  (CleanPageHistory.))
