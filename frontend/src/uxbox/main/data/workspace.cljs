;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.data.workspace
  (:require [cljs.spec :as s]
            [beicon.core :as rx]
            [potok.core :as ptk]
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
            [uxbox.main.data.workspace.ruler :as wruler]
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
(def start-ruler wruler/start-ruler)
(def clear-ruler wruler/clear-ruler)

;; --- Initialize Workspace

(declare initialize-alignment)

(defrecord Initialize [project-id page-id]
  ptk/UpdateEvent
  (update [_ state]
    (let [default-flags #{:sitemap :drawtools :layers :element-options :rules}]
      (if (:workspace state)
        (update state :workspace merge
                {:project project-id
                 :page page-id
                 :selected #{}
                 :drawing nil
                 :drawing-tool nil
                 :tooltip nil})
        (assoc state :workspace
               {:project project-id
                :zoom 1
                :page page-id
                :flags default-flags
                :selected #{}
                :drawing nil
                :drawing-tool nil
                :tooltip nil}))))

  ptk/WatchEvent
  (watch [_ state stream]
    (let [page (get-in state [:pages page-id])]

      ;; Activate loaded if page is not fetched.
      (when-not page (reset! st/loader true))

      (if page
        (rx/of (initialize-alignment page-id))
        (rx/merge
         (rx/of (udp/fetch-pages project-id))
         (->> stream
              (rx/filter udp/pages-fetched?)
              (rx/take 1)
              (rx/do #(reset! st/loader false))
              (rx/map #(initialize-alignment page-id)))))))

  ptk/EffectEvent
  (effect [_ state stream]
    ;; Optimistic prefetch of projects if them are not already fetched
    (when-not (seq (:projects state))
      (st/emit! (dp/fetch-projects)))))

(defn initialize
  "Initialize the workspace state."
  [project page]
  {:pre [(uuid? project)
         (uuid? page)]}
  (Initialize. project page))

;; --- Workspace Tooltips

(defrecord SetTooltip [text]
  ptk/UpdateEvent
  (update [_ state]
    (assoc-in state [:workspace :tooltip] text)))

(defn set-tooltip
  [text]
  (SetTooltip. text))

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

;; --- Workspace Ruler

(deftype ActivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (set-tooltip "Drag to use the ruler")
           (activate-flag :ruler))))

(defn activate-ruler
  []
  (ActivateRuler.))

(deftype DeactivateRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (set-tooltip nil)
           (deactivate-flag :ruler))))

(defn deactivate-ruler
  []
  (DeactivateRuler.))

(deftype ToggleRuler []
  ptk/WatchEvent
  (watch [_ state stream]
    (let [flags (get-in state [:workspace :flags])]
      (if (contains? flags :ruler)
        (rx/of (deactivate-ruler))
        (rx/of (activate-ruler))))))

(defn toggle-ruler
  []
  (ToggleRuler.))

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
                        empty
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

;; --- Grid Alignment

(defrecord InitializeAlignment [id]
  ptk/WatchEvent
  (watch [_ state stream]
    (let [{:keys [metadata] :as page} (get-in state [:pages id])
          params {:width c/viewport-width
                  :height c/viewport-height
                  :x-axis (:grid-x-axis metadata c/grid-x-axis)
                  :y-axis (:grid-y-axis metadata c/grid-y-axis)}]
      (rx/concat
       (rx/of (deactivate-flag :grid-indexed))
       (->> (uwrk/initialize-alignment params)
            (rx/map #(activate-flag :grid-indexed)))))))

(defn initialize-alignment?
  [v]
  (instance? InitializeAlignment v))

(defn initialize-alignment
  [id]
  {:pre [(uuid? id)]}
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
