;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.view.data.viewer
  (:require [beicon.core :as rx]
            [potok.core :as ptk]
            [uxbox.util.router :as rt]
            [uxbox.util.data :refer (parse-int)]
            [uxbox.main.repo :as rp]
            [uxbox.main.data.pages :as udpg]
            [uxbox.main.data.projects :as udpj]))

;; --- Initialize

(declare load-data)

(defrecord Initialize [token]
  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (load-data token))))

(defn initialize
  "Initialize the viewer state."
  [token]
  (Initialize. token))

;; --- Data Loaded

(defn- unpack-page
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
        (update :pages conj page))))

(defrecord DataLoaded [data]
  ptk/UpdateEvent
  (update [_ state]
    (let [project (dissoc data :pages)
          get-order #(get-in % [:metadata :order])
          pages (sort-by get-order (:pages data))]
      (as-> state $
        (assoc $ :project project)
        (assoc $ :pages [])
        (reduce unpack-page $ pages)))))

(defn data-loaded
  [data]
  (DataLoaded. data))

;; --- Load Data

(defrecord LoadData [token]
  ptk/WatchEvent
  (watch [_ state stream]
    (->> (rp/req :fetch/project-by-token token)
         (rx/map :payload)
         (rx/map data-loaded))))

(defn load-data
  [token]
  (LoadData. token))

;; --- Select Page

(defrecord SelectPage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [token (get-in state [:route :params :path :token])]
      (rx/of (rt/nav :view/viewer {:token token :id id})))))

(defn select-page
  [id]
  (SelectPage. id))

;; --- Go to Page

(defrecord GoToPage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [token (get-in state [:route :params :path :token])]
      (rx/of (rt/nav :view/viewer {:token token :id id})))))

(defn go-to-page
  [id]
  (GoToPage. id))

;; --- Toggle Flag

(defrecord ToggleFlag [key]
  ptk/UpdateEvent
  (update [_ state]
    (let [flags (:flags state #{})]
      (if (contains? flags key)
        (assoc state :flags (disj flags key))
        (assoc state :flags (conj flags key))))))

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  {:pre [(keyword? key)]}
  (ToggleFlag. key))

;; --- Fetch Image

(declare image-fetched)

(defrecord FetchImage [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [existing (get-in state [:images id])]
      (if existing
        (rx/empty)
        (->> (rp/req :fetch/image {:id id})
             (rx/map :payload)
             (rx/map image-fetched))))))

(defn fetch-image
  "Conditionally fetch image by its id. If image
  is already loaded, this event is noop."
  [id]
  {:pre [(uuid? id)]}
  (FetchImage. id))

;; --- Image Fetched

(defrecord ImageFetched [image]
  ptk/UpdateEvent
  (update [_ state]
    (let [id (:id image)]
      (update state :images assoc id image))))

(defn image-fetched
  [image]
  {:pre [(map? image)]}
  (ImageFetched. image))
