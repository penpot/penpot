;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace
  (:require [cljs.spec :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
            [lentes.core :as l]
            [uxbox.main.store :as st]
            [uxbox.main.constants :as c]
            [uxbox.main.lenses :as ul]
            [uxbox.main.workers :as uwrk]
            [uxbox.main.data.projects :as dp]
            [uxbox.main.data.pages :as udp]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.data.icons :as udi]
            [uxbox.main.data.shapes-impl :as shimpl]
            [uxbox.main.data.lightbox :as udl]
            [uxbox.main.data.history :as udh]
            [uxbox.main.data.workspace.scroll :as wscroll]
            [uxbox.main.data.workspace.drawing :as wdrawing]
            [uxbox.main.data.workspace.selrect :as wselrect]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.spec :as us]
            [uxbox.util.forms :as sc]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.time :as dt]
            [uxbox.util.math :as mth]
            [uxbox.util.data :refer (index-of)]))

;; --- Expose inner functions

(def start-viewport-positioning wscroll/start-viewport-positioning)
(def stop-viewport-positioning wscroll/stop-viewport-positioning)
(def start-drawing wdrawing/start-drawing)
(def close-drawing-path wdrawing/close-drawing-path)
(def select-for-drawing wdrawing/select-for-drawing)
(def start-selrect wselrect/start-selrect)

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
               :drawing nil
               :tooltip nil})
      (assoc state :workspace
             {:project project
              :zoom 1
              :page page
              :flags #{:sitemap :drawtools :layers :element-options}
              :selected #{}
              :drawing nil
              :tooltip nil})))

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

;; --- Workspace Flags

(deftype ActivateFlag [flag]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :flags] conj flag)))

(defn activate-flag
  [flag]
  {:pre [(keyword? flag)]}
  (ActivateFlag. flag))

(deftype DeactivateFlag [flag]
  ptk/UpdateEvent
  (update [_ state]
    (update-in state [:workspace :flags] disj flag)))

(defn deactivate-flag
  [flag]
  {:pre [(keyword? flag)]}
  (DeactivateFlag. flag))

(deftype ToggleFlag [flag]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [flags (get-in state [:workspace :flags])]
      (if (contains? flags flag)
        (rx/of (deactivate-flag flag))
        (rx/of (activate-flag flag))))))

(defn toggle-flag
  [flag]
  (ToggleFlag. flag))

;; --- Icons Toolbox

(deftype SelectIconsToolboxCollection [id]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :icons-toolbox] id))

  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (udi/fetch-icons id))))

(defn select-icons-toolbox-collection
  [id]
  {:pre [(or (nil? id) (uuid? id))]}
  (SelectIconsToolboxCollection. id))

(deftype InitializeIconsToolbox []
  ptk/UpdateEvent
  (update [_ state]
    state)

  ptk/WatchEvent
  (watch [_ state stream]
    (letfn [(get-first-with-icons [colls]
              (->> (sort-by :name colls)
                   (filter #(> (:num-icons %) 0))
                   (first)
                   (:id)))
            (on-fetched [event]
              (let [coll (get-first-with-icons @event)]
                (println "first" coll)
                (select-icons-toolbox-collection coll)))]
      (rx/merge
       (rx/of (udi/fetch-collections)
              (udi/fetch-icons nil))

       ;; Only perform the autoselection if it is not
       ;; previously already selected by the user.
       (when-not (contains? (:workspace state) :icons-toolbox)
         (->> stream
              (rx/filter udi/collections-fetched?)
              (rx/take 1)
              (rx/map on-fetched)))))))

(defn initialize-icons-toolbox
  []
  (InitializeIconsToolbox.))

;; --- Clipboard Management

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

;; --- Zoom Management

(deftype IncreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [increase #(nth c/zoom-levels
                         (+ (index-of c/zoom-levels %) 1)
                         (last c/zoom-levels))]
      (update-in state [:workspace :zoom] (fnil increase 1)))))

(defn increase-zoom
  []
  (IncreaseZoom.))

(deftype DecreaseZoom []
  ptk/UpdateEvent
  (update [_ state]
    (let [decrease #(nth c/zoom-levels
                         (- (index-of c/zoom-levels %) 1)
                         (first c/zoom-levels))]
      (update-in state [:workspace :zoom] (fnil decrease 1)))))

(defn decrease-zoom
  []
  (DecreaseZoom.))

(deftype ResetZoom []
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :zoom] 1)))

(defn reset-zoom
  []
  (ResetZoom.))

;; --- Tooltips

(defrecord SetTooltip [text]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :tooltip] text)))

(defn set-tooltip
  [text]
  (SetTooltip. text))

;; --- Grid Alignment

(declare initialize-alignment?)

(defrecord InitializeAlignment [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (get-in state [:pages id])
          opts (:metadata page)
          params {:width c/viewport-width
                  :height c/viewport-height
                  :x-axis (:grid-x-axis opts c/grid-x-axis)
                  :y-axis (:grid-y-axis opts c/grid-y-axis)}
          stoper (->> (rx/filter initialize-alignment? stream)
                      (rx/take 1))]
      (->> (rx/just nil)
           (rx/delay 1000)
           (rx/take-until stoper)
           (rx/flat-map (fn [_]
                          (rx/merge (->> (uwrk/initialize-alignment params)
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
