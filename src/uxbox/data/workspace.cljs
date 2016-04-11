;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.data.workspace
  (:require [bouncer.validators :as v]
            [beicon.core :as rx]
            [uxbox.constants :as c]
            [uxbox.shapes :as sh]
            [uxbox.rstore :as rs]
            [uxbox.state.shapes :as stsh]
            [uxbox.schema :as sc]
            [uxbox.data.core :refer (worker)]
            [uxbox.data.pages :as udp]
            [uxbox.data.shapes :as uds]
            [uxbox.util.datetime :as dt]
            [uxbox.util.math :as mth]
            [uxbox.util.data :refer (index-of)]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.workers :as uw]))

;; --- Initialize Workspace

(declare initialize-alignment-index)

(defonce zoom-levels [0.20 0.21 0.22 0.23 0.24 0.25 0.27 0.28 0.30 0.32 0.34
                      0.36 0.38 0.40 0.42 0.44 0.46 0.48 0.51 0.54 0.57 0.60
                      0.63 0.66 0.69 0.73 0.77 0.81 0.85 0.90 0.95 1.00 1.05
                      1.10 1.15 1.21 1.27 1.33 1.40 1.47 1.54 1.62 1.70 1.78
                      1.87 1.96 2.06 2.16 2.27 2.38 2.50 2.62 2.75 2.88 3.00])

(defrecord InitializeWorkspace [project page]
  rs/UpdateEvent
  (-apply-update [_ state]
    (if (:workspace state)
      (update state :workspace merge
              {:project project
               :page page
               :selected #{}
               :drawing nil})
      (assoc state :workspace
             {:project project
              :zoom 1
              :page page
              :flags #{:layers :element-options}
              :selected #{}
              :drawing nil})))

    rs/WatchEvent
    (-apply-watch [_ state s]
      (if (get-in state [:pages-by-id page])
        (rx/of (initialize-alignment-index page))
        (->> (rx/filter udp/pages-fetched? s)
             (rx/take 1)
             (rx/map #(initialize-alignment-index page))))))

(defn initialize
  "Initialize the workspace state."
  [project page]
  (InitializeWorkspace. project page))

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [flags (get-in state [:workspace :flags])]
        (if (contains? flags key)
          (assoc-in state [:workspace :flags] (disj flags key))
          (assoc-in state [:workspace :flags] (conj flags key)))))))

(defn select-for-drawing
  "Mark a shape selected for drawing in the canvas."
  [shape]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if shape
        (assoc-in state [:workspace :drawing] shape)
        (update-in state [:workspace] dissoc :drawing)))))

;; --- Copy to Clipboard

(defrecord CopyToClipboard []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [selected (get-in state [:workspace :selected])
          item {:id (random-uuid)
                :created-at (dt/now)
                :items selected}
          clipboard (-> (:clipboard state)
                        (conj item))]
      (assoc state :clipboard
             (if (> (count clipboard) 5)
               (pop clipboard)
               clipboard)))))

(defn copy-to-clipboard
  "Copy selected shapes to clipboard."
  []
  (CopyToClipboard.))

;; --- Paste from Clipboard

(defrecord PasteFromClipboard [id]
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [page (get-in state [:workspace :page])
          selected (if (nil? id)
                     (first (:clipboard state))
                     (->> (:clipboard state)
                          (filter #(= id (:id %)))
                          (first)))]
      (stsh/duplicate-shapes state (:items selected) page))))

(defn paste-from-clipboard
  "Copy selected shapes to clipboard."
  ([] (PasteFromClipboard. nil))
  ([id] (PasteFromClipboard. id)))

;; --- Increase Zoom

(defrecord IncreaseZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [increase #(nth zoom-levels (+ (index-of zoom-levels %) 1) (last zoom-levels))]
      (update-in state [:workspace :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

;; --- Decrease Zoom

(defrecord DecreaseZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (let [decrease #(nth zoom-levels (- (index-of zoom-levels %) 1) (first zoom-levels))]
      (update-in state [:workspace :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

;; --- Reset Zoom

(defrecord ResetZoom []
  rs/UpdateEvent
  (-apply-update [_ state]
    (assoc-in state [:workspace :zoom] 1)))

(defn reset-zoom
  []
  (ResetZoom.))

;; --- Initialize Alignment Index

(defrecord InitializeAlignmentIndex [id]
  rs/WatchEvent
  (-apply-watch [_ state s]
    (let [page (get-in state [:pages-by-id id])
          opts (:options page)
          message {:cmd :grid/init
                   :width c/viewport-width
                   :height c/viewport-height
                   :x-axis (:grid/x-axis opts c/grid-x-axis)
                   :y-axis (:grid/y-axis opts c/grid-y-axis)}]
      (->> (uw/send! worker message)
           (rx/map #(toggle-flag :alignment/indexed))))))

(defn initialize-alignment-index
  [id]
  (InitializeAlignmentIndex. id))

