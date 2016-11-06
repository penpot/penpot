;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.data.viewer
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.router :as rt]
            [uxbox.util.schema :as sc]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.pages :as udpg]
            [uxbox.main.data.projects :as udpj]))

;; --- Initialize

(declare load-data)

(defrecord Initialize [token]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (rx/of (load-data token))))

(defn initialize
  "Initialize the viewer state."
  [token]
  (Initialize. token))

;; --- Data Loaded

(defn- unpack-page
  "Unpacks packed page object and assocs it to the
  provided state."
  [state page]
  (let [data (:data page)
        shapes (:shapes data)
        shapes-by-id (:shapes data)
        page (-> page
                 (dissoc page :data)
                 (assoc :shapes shapes))]
    (-> state
        (update :shapes merge shapes-by-id)
        (update :pages conj page))))

(defrecord DataLoaded [data]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [project (dissoc data :pages)
          pages (sort-by :created-at (:pages data))]
      (as-> state $
        (assoc $ :project project)
        (assoc $ :pages [])
        (reduce unpack-page $ pages)))))

(defn data-loaded
  [data]
  (DataLoaded. data))

;; --- Load Data

(defrecord LoadData [token]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (->> (rp/req :fetch/project-by-token token)
         (rx/map :payload)
         (rx/map data-loaded))))

(defn load-data
  [token]
  (LoadData. token))

;; --- Select Page

(defrecord SelectPage [index]
  rs/WatchEvent
  (-apply-watch [_ state stream]
    (let [token (get-in state [:route :params :token])]
      (rx/of (rt/navigate :view/viewer {:token token :id index})))))

(defn select-page
  [index]
  (SelectPage. index))

;; --- Toggle Flag

(defrecord ToggleFlag [key]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [flags (:flags state #{})]
      (if (contains? flags key)
        (assoc state :flags (disj flags key))
        (assoc state :flags (conj flags key))))))

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  {:pre [(keyword? key)]}
  (ToggleFlag. key))


