;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing
  "Drawing interactions."
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.spec :as us]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.data.workspace.drawing.path :as path]
   [app.main.data.workspace.drawing.curve :as curve]
   [app.main.data.workspace.drawing.box :as box]))

(declare start-drawing)
(declare handle-drawing)

;; --- Select for Drawing

(defn select-for-drawing
  ([tool] (select-for-drawing tool nil))
  ([tool data]
   (ptk/reify ::select-for-drawing
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-drawing assoc :tool tool :object data))

     ptk/WatchEvent
     (watch [_ state stream]
       (let [stoper (rx/filter (ptk/type? ::clear-drawing) stream)]
         (rx/merge
          (rx/of (dws/deselect-all))

          (when (= tool :path)
            (rx/of (start-drawing :path)))

          ;; NOTE: comments are a special case and they manage they
          ;; own interrupt cycle.q
          (when (and (not= tool :comments)
                     (not= tool :path))
            (->> stream
                 (rx/filter dwc/interrupt?)
                 (rx/take 1)
                 (rx/map (constantly common/clear-drawing))
                 (rx/take-until stoper)))))))))


;; NOTE/TODO: when an exception is raised in some point of drawing the
;; draw lock is not released so the user need to refresh in order to
;; be able draw again. THIS NEED TO BE REVISITED

(defn start-drawing
  [type]
  {:pre [(keyword? type)]}
  (let [lock-id (uuid/next)]
    (ptk/reify ::start-drawing
      ptk/UpdateEvent
      (update [_ state]
        (update-in state [:workspace-drawing :lock] #(if (nil? %) lock-id %)))

      ptk/WatchEvent
      (watch [_ state stream]
        (let [lock (get-in state [:workspace-drawing :lock])]
          (when (= lock lock-id)
            (rx/merge
             (rx/of (handle-drawing type))
             (->> stream
                  (rx/filter (ptk/type? ::common/handle-finish-drawing) )
                  (rx/first)
                  (rx/map #(fn [state] (update state :workspace-drawing dissoc :lock)))))))))))

(defn handle-drawing
  [type]
  (ptk/reify ::handle-drawing
    ptk/UpdateEvent
    (update [_ state]
      (let [data (cp/make-minimal-shape type)]
        (update-in state [:workspace-drawing :object] merge data)))

    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (case type
               :path
               (path/handle-new-shape)

               :curve
               (curve/handle-drawing-curve)

               ;; default
               (box/handle-drawing-box))))))



