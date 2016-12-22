;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace
  (:require [cljs.spec :as s]
            [beicon.core :as rx]
            [uxbox.util.uuid :as uuid]
            [uxbox.main.constants :as c]
            [potok.core :as ptk]
            [uxbox.util.spec :as us]
            [uxbox.util.forms :as sc]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.workers :as uw]
            [uxbox.store :as st]
            [uxbox.main.data.core :refer (worker)]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.shapes-impl :as shimpl]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.history :as udh]
            [uxbox.util.datetime :as dt]
            [uxbox.util.math :as mth]
            [uxbox.util.data :refer (index-of)]))

;; --- Constants

(def zoom-levels
  [0.20 0.21 0.22 0.23 0.24 0.25 0.27 0.28 0.30 0.32 0.34
   0.36 0.38 0.40 0.42 0.44 0.46 0.48 0.51 0.54 0.57 0.60
   0.63 0.66 0.69 0.73 0.77 0.81 0.85 0.90 0.95 1.00 1.05
   1.10 1.15 1.21 1.27 1.33 1.40 1.47 1.54 1.62 1.70 1.78
   1.87 1.96 2.06 2.16 2.27 2.38 2.50 2.62 2.75 2.88 3.00])

;; --- Initialize Workspace

(declare initialize-alignment)

(defrecord InitializeWorkspace [project page]
  ptk/UpdateEvent
  (update [_ state]
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
              :flags #{:sitemap :drawtools :layers :element-options}
              :selected #{}
              :drawing nil})))

  ptk/WatchEvent
  (watch [_ state s]
    (let [page-id page
          page (get-in state [:pages page-id])]

      ;; Activate loaded if page is not fetched.
      (when-not page (reset! st/loader true))

      (rx/merge
       (if page
         (rx/of (initialize-alignment page-id))
         (rx/merge
          (rx/of (udp/fetch-pages project))
          (->> (rx/filter udp/pages-fetched? s)
               (rx/take 1)
               (rx/do #(reset! st/loader false))
               (rx/map #(initialize-alignment page-id)))))

       ;; Initial history loading
       (rx/of
        (udh/fetch-page-history page-id)
        (udh/fetch-pinned-page-history page-id)))))

  ptk/EffectEvent
  (effect [_ state stream]
    ;; Optimistic prefetch of projects if them are not already fetched
    (when-not (seq (:projects state))
      (st/emit! (dp/fetch-projects)))))

(defn initialize
  "Initialize the workspace state."
  [project page]
  (InitializeWorkspace. project page))

;; --- Toggle Flag

(defn toggle-flag
  "Toggle the enabled flag of the specified tool."
  [key]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [flags (get-in state [:workspace :flags])]
        (if (contains? flags key)
          (assoc-in state [:workspace :flags] (disj flags key))
          (assoc-in state [:workspace :flags] (conj flags key)))))))

(defn select-for-drawing
  "Mark a shape selected for drawing in the canvas."
  [shape]
  (reify
    ptk/UpdateEvent
    (update [_ state]
      (let [current (get-in state [:workspace :drawing])]
        (if (or (nil? shape)
                (= shape current))
          (update state :workspace dissoc :drawing)
          (assoc-in state [:workspace :drawing] shape))))))

;; --- Activate Workspace Flag

(defrecord ActivateFlag [flag]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :flags] conj flag)))

(defn activate-flag
  [flag]
  (ActivateFlag. flag))

;; --- Copy to Clipboard

(defrecord CopyToClipboard []
  ptk/UpdateEvent
  (update [_ state]
    (let [selected (get-in state [:workspace :selected])
          item {:id (uuid/random)
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
  ptk/UpdateEvent
  (update [_ state]
    (let [page (get-in state [:workspace :page])
          selected (if (nil? id)
                     (first (:clipboard state))
                     (->> (:clipboard state)
                          (filter #(= id (:id %)))
                          (first)))]
      (shimpl/duplicate-shapes state (:items selected) page))))

(defn paste-from-clipboard
  "Copy selected shapes to clipboard."
  ([] (PasteFromClipboard. nil))
  ([id] (PasteFromClipboard. id)))

;; --- Increase Zoom

(defrecord IncreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [increase #(nth zoom-levels
                         (+ (index-of zoom-levels %) 1)
                         (last zoom-levels))]
      (update-in state [:workspace :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

;; --- Decrease Zoom

(defrecord DecreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [decrease #(nth zoom-levels
                         (- (index-of zoom-levels %) 1)
                         (first zoom-levels))]
      (update-in state [:workspace :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

;; --- Reset Zoom

(defrecord ResetZoom []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :zoom] 1)))

(defn reset-zoom
  []
  (ResetZoom.))

;; --- Set tooltip

(defrecord SetTooltip [text]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :tooltip] text)))

(defn set-tooltip
  [text]
  (SetTooltip. text))

;; --- Initialize Alignment Index

(declare initialize-alignment?)

(defrecord InitializeAlignment [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (get-in state [:pages id])
          opts (:metadata page)
          message {:cmd :grid-init
                   :width c/viewport-width
                   :height c/viewport-height
                   :x-axis (:grid-x-axis opts c/grid-x-axis)
                   :y-axis (:grid-y-axis opts c/grid-y-axis)}
          stoper (->> (rx/filter initialize-alignment? stream)
                      (rx/take 1))]
      (->> (rx/just nil)
           (rx/delay 1000)
           (rx/take-until stoper)
           (rx/flat-map (fn [_]
                          (rx/merge (->> (uw/send! worker message)
                                         (rx/map #(activate-flag :grid-indexed)))
                                    (when (:grid-alignment opts)
                                      (rx/of (activate-flag :grid-alignment))))))))))

(defn initialize-alignment?
  [v]
  (instance? InitializeAlignment v))

(defn initialize-alignment
  [id]
  (InitializeAlignment. id))

;; --- Update Metadata

;; Is a workspace aware wrapper over uxbox.data.pages/UpdateMetadata event.

(defrecord UpdateMetadata [id metadata]
  ptk/WatchEvent
  (watch [_ state s]
    (rx/of (udp/update-metadata id metadata)
           (initialize-alignment id))))

(defn update-metadata
  [id metadata]
  {:pre [(uuid? id) (us/valid? ::udp/metadata metadata)]}
  (UpdateMetadata. id metadata))
