;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.drawing
  "Drawing interactions."
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.box :as box]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.data.workspace.drawing.curve :as curve]
   [app.main.data.workspace.path :as path]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(declare start-drawing)
(declare handle-drawing)

;; --- Select for Drawing

(defn select-for-drawing
  [tool]
  (ptk/reify ::select-for-drawing
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update :workspace-layout (fn [workspace-layout]
                                      (if (= tool :comments)
                                        (disj workspace-layout :document-history)
                                        workspace-layout)))
          (update :workspace-drawing assoc :tool tool)
          ;; When changing drawing tool disable "scale text" mode
          ;; automatically, to help users that ignore how this
          ;; mode works.
          (update :workspace-layout disj :scale-text)))

    ptk/WatchEvent
    (watch [_ _ stream]
      (rx/merge
       (when (= tool :path)
         (rx/of (start-drawing :path)))

       (when (= tool :curve)
         (let [stopper (rx/filter dwc/interrupt? stream)]
           (->> stream
                (rx/filter (ptk/type? ::common/handle-finish-drawing))
                (rx/map (constantly tool))
                (rx/take 1)
                (rx/observe-on :async)
                (rx/map select-for-drawing)
                (rx/take-until stopper))))

       ;; NOTE: comments are a special case and they manage they
       ;; own interrupt cycle.
       (when (and (not= tool :comments)
                  (not= tool :path))
         (let [stopper (rx/filter (ptk/type? ::clear-drawing) stream)]
           (->> stream
                (rx/filter dwc/interrupt?)
                (rx/take 1)
                (rx/map common/clear-drawing)
                (rx/take-until stopper))))))))

;; NOTE/TODO: when an exception is raised in some point of drawing the
;; draw lock is not released so the user need to refresh in order to
;; be able draw again. THIS NEED TO BE REVISITED

(defn start-drawing
  [type]
  (dm/assert! (keyword? type))
  (let [lock-id (uuid/next)]
    (ptk/reify ::start-drawing
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace-drawing :lock] #(if (nil? %) lock-id %)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [lock (dm/get-in state [:workspace-drawing :lock])]
          (when (= lock lock-id)
            (rx/merge
             (rx/of (handle-drawing type))
             (->> stream
                  (rx/filter (ptk/type? ::common/handle-finish-drawing))
                  (rx/take 1)
                  (rx/map #(fn [state] (update state :workspace-drawing dissoc :lock)))))))))))

(defn handle-drawing
  [type]
  (ptk/reify ::handle-drawing
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (case type
         :path (path/handle-drawing)
         :curve (curve/handle-drawing)
         (box/handle-drawing type))))))

(defn change-orientation
  [orientation]

  (assert
   (contains? #{:horizontal :vertical} orientation)
   "expected valid orientation")

  (ptk/reify ::change-orientation
    ptk/UpdateEvent
    (update [_ state]
      (let [{:keys [width height]}
            (get state :workspace-drawing)

            width'
            (if (= orientation :vertical)
              (mth/min width height)
              (mth/max width height))

            height'
            (if (= orientation :vertical)
              (mth/max width height)
              (mth/min width height))]

        (update state :workspace-drawing assoc :width width' :height height')))))

(defn set-default-size
  [width height]
  (ptk/reify ::change-preset
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-drawing assoc :width width :height height))))



