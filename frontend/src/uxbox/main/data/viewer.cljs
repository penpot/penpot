;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.data.viewer
  (:require
   [cljs.spec.alpha :as s]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [uxbox.main.constants :as c]
   [uxbox.main.repo :as rp]
   [uxbox.common.spec :as us]
   [uxbox.common.pages :as cp]
   [uxbox.common.data :as d]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.uuid :as uuid]))

;; --- Specs

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)

(s/def ::project (s/keys ::req-un [::id ::name]))
(s/def ::file (s/keys :req-un [::id ::name]))
(s/def ::page (s/keys :req-un [::id ::name ::cp/data]))

(s/def ::bundle
  (s/keys :req-un [::project ::file ::page]))


;; --- Initialization

(declare fetch-bundle)
(declare bundle-fetched)

(defn initialize
  [page-id]
  (ptk/reify ::initialize
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :viewer-local {:zoom 1}))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (fetch-bundle page-id)))))

;; --- Data Fetching

(defn fetch-bundle
  [page-id]
  (ptk/reify ::fetch-file
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rp/query :viewer-bundle-by-page-id {:page-id page-id})
           (rx/map bundle-fetched)))))


(defn- extract-frames
  [page]
  (let [objects (get-in page [:data :objects])
        root (get objects uuid/zero)]
    (->> (:shapes root)
         (map #(get objects %))
         (filter #(= :frame (:type %)))
         (vec))))

(defn bundle-fetched
  [{:keys [project file page images] :as bundle}]
  (us/verify ::bundle bundle)
  (ptk/reify ::file-fetched
    ptk/UpdateEvent
    (update [_ state]
      (let [frames (extract-frames page)
            objects (get-in page [:data :objects])]
        (assoc state :viewer-data {:project project
                                   :objects objects
                                   :file file
                                   :page page
                                   :images images
                                   :frames frames})))))

;; --- Zoom Management

(def increase-zoom
  (ptk/reify ::increase-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [increase #(nth c/zoom-levels
                           (+ (d/index-of c/zoom-levels %) 1)
                           (last c/zoom-levels))]
        (update-in state [:viewer-local :zoom] (fnil increase 1))))))

(def decrease-zoom
  (ptk/reify ::decrease-zoom
    ptk/UpdateEvent
    (update [_ state]
      (let [decrease #(nth c/zoom-levels
                           (- (d/index-of c/zoom-levels %) 1)
                           (first c/zoom-levels))]
        (update-in state [:viewer-local :zoom] (fnil decrease 1))))))

(def reset-zoom
  (ptk/reify ::reset-zoom
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 1))))

(def zoom-to-50
  (ptk/reify ::zoom-to-50
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 0.5))))

(def zoom-to-200
  (ptk/reify ::zoom-to-200
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:viewer-local :zoom] 2))))

;; --- Local State Management

(def toggle-thumbnails-panel
  (ptk/reify ::toggle-thumbnails-panel
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:viewer-local :show-thumbnails] not))))
