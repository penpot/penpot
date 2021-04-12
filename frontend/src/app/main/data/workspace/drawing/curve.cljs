;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.drawing.curve
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.path :as gsp]
   [app.main.streams :as ms]
   [app.util.geom.path :as path]
   [app.main.data.workspace.drawing.common :as common]
   [app.main.data.workspace.common :as dwc]
   [app.common.pages :as cp]))

(def simplify-tolerance 0.3)

(defn stoper-event? [{:keys [type shift] :as event}]
  (ms/mouse-event? event) (= type :up))

(defn initialize-drawing [state]
  (assoc-in state [:workspace-drawing :object :initialized?] true))

(defn insert-point-segment [state point]
  (let [segments (-> state
                     (get-in [:workspace-drawing :object :segments])
                     (or [])
                     (conj point))
        content (gsp/segments->content segments)
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]
    (-> state
        (update-in [:workspace-drawing :object] assoc
                   :segments segments
                   :content content
                   :selrect selrect
                   :points points))))

(defn setup-frame-curve []
  (ptk/reify ::setup-frame-path
    ptk/UpdateEvent
    (update [_ state]

      (let [objects (dwc/lookup-page-objects state)
            content (get-in state [:workspace-drawing :object :content] [])
            position (get-in content [0 :params] nil)
            frame-id  (cp/frame-id-by-position objects position)]
        (-> state
            (assoc-in [:workspace-drawing :object :frame-id] frame-id))))))

(defn curve-to-path [{:keys [segments] :as shape}]
  (let [content (gsp/segments->content segments)
        selrect (gsh/content->selrect content)
        points (gsh/rect->points selrect)]
    (-> shape
        (dissoc :segments)
        (assoc :content content)
        (assoc :selrect selrect)
        (assoc :points points))))

(defn finish-drawing-curve [state]
  (update-in
   state [:workspace-drawing :object]
   (fn [shape]
     (-> shape
         (update :segments #(path/simplify % simplify-tolerance))
         (curve-to-path)))))

(defn handle-drawing-curve []
  (ptk/reify ::handle-drawing-curve
    ptk/WatchEvent
    (watch [_ state stream]
      (let [{:keys [flags]} (:workspace-local state)
            stoper (rx/filter stoper-event? stream)
            mouse  (rx/sample 10 ms/mouse-position)]
        (rx/concat
         (rx/of initialize-drawing)
         (->> mouse
              (rx/map (fn [pt] #(insert-point-segment % pt)))
              (rx/take-until stoper))
         (rx/of (setup-frame-curve)
                finish-drawing-curve
                common/handle-finish-drawing))))))
