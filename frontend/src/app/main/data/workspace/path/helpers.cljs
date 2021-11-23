;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.helpers
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.path.commands :as upc]
   [app.common.path.subpaths :as ups]
   [app.main.data.workspace.path.common :as common]
   [app.main.streams :as ms]
   [potok.core :as ptk]))

(defn end-path-event? [event]
  (or (= (ptk/type event) ::common/finish-path)
      (= (ptk/type event) :app.main.data.workspace.path.shortcuts/esc-pressed)
      (= :app.main.data.workspace.common/clear-edition-mode (ptk/type event))
      (= :app.main.data.workspace/finalize-page (ptk/type event))
      (= event :interrupt) ;; ESC
      (ms/mouse-double-click? event)))

(defn content-center
  [content]
  (-> content
      gsh/content->selrect
      gsh/center-selrect))

(defn content->points+selrect
  "Given the content of a shape, calculate its points and selrect"
  [shape content]
  (let [{:keys [flip-x flip-y]} shape
        transform
        (cond-> (:transform shape (gmt/matrix))
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1)))

        transform-inverse
        (cond-> (gmt/matrix)
          flip-x (gmt/scale (gpt/point -1 1))
          flip-y (gmt/scale (gpt/point 1 -1))
          :always (gmt/multiply (:transform-inverse shape (gmt/matrix))))

        center (or (gsh/center-shape shape)
                   (content-center content))

        base-content (gsh/transform-content
                      content
                      (gmt/transform-in center transform-inverse))

        ;; Calculates the new selrect with points given the old center
        points (-> (gsh/content->selrect base-content)
                   (gsh/rect->points)
                   (gsh/transform-points center transform))

        points-center (gsh/center-points points)

        ;; Points is now the selrect but the center is different so we can create the selrect
        ;; through points
        selrect (-> points
                    (gsh/transform-points points-center transform-inverse)
                    (gsh/points->selrect))]
    [points selrect]))

(defn update-selrect
  "Updates the selrect and points for a path"
  [shape]
  (let [[points selrect] (content->points+selrect shape (:content shape))]
    (assoc shape :points points :selrect selrect)))

(defn closest-angle
  [angle]
  (cond
    (or  (> angle 337.5)  (<= angle 22.5))  0
    (and (> angle 22.5)   (<= angle 67.5))  45
    (and (> angle 67.5)   (<= angle 112.5)) 90
    (and (> angle 112.5)	(<= angle 157.5)) 135
    (and (> angle 157.5)	(<= angle 202.5)) 180
    (and (> angle 202.5)	(<= angle 247.5)) 225
    (and (> angle 247.5)	(<= angle 292.5)) 270
    (and (> angle 292.5)	(<= angle 337.5)) 315))

(defn position-fixed-angle [point from-point]
  (if (and from-point point)
    (let [angle (mod (+ 360 (- (gpt/angle point from-point))) 360)
          to-angle (closest-angle angle)
          distance (gpt/distance point from-point)]
      (gpt/angle->point from-point (mth/radians to-angle) distance))
    point))

(defn next-node
  "Calculates the next-node to be inserted."
  [shape position prev-point prev-handler]
  (let [position (select-keys position [:x :y])
        last-command (-> shape :content last :command)
        add-line?   (and prev-point (not prev-handler) (not= last-command :close-path))
        add-curve?  (and prev-point prev-handler (not= last-command :close-path))]
    (cond
      add-line?   {:command :line-to
                   :params position}
      add-curve?  {:command :curve-to
                   :params (upc/make-curve-params position prev-handler)}
      :else       {:command :move-to
                   :params position})))

(defn append-node
  "Creates a new node in the path. Usually used when drawing."
  [shape position prev-point prev-handler]
  (let [command (next-node shape position prev-point prev-handler)]
    (-> shape
        (update :content (fnil conj []) command)
        (update :content ups/close-subpaths)
        (update-selrect))))

(defn angle-points [common p1 p2]
  (mth/abs
   (gpt/angle-with-other
    (gpt/to-vec common p1)
    (gpt/to-vec common p2))))

(defn calculate-opposite-delta [node handler opposite match-angle? match-distance? dx dy]
  (when (and (some? handler) (some? opposite))
    (let [;; To match the angle, the angle should be matching (angle between points 180deg)
          angle-handlers (angle-points node handler opposite)

          match-angle? (and match-angle? (<= (mth/abs (- 180 angle-handlers) ) 0.1))

          ;; To match distance the distance should be matching
          match-distance? (and match-distance? (mth/almost-zero? (- (gpt/distance node handler)
                                                                    (gpt/distance node opposite))))

          new-handler (-> handler (update :x + dx) (update :y + dy))

          v1 (gpt/to-vec node handler)
          v2 (gpt/to-vec node new-handler)

          delta-angle (gpt/angle-with-other v1 v2)
          delta-sign (gpt/angle-sign v1 v2)

          distance-scale (/ (gpt/distance node handler)
                            (gpt/distance node new-handler))

          new-opposite (cond-> opposite
                         match-angle?
                         (gpt/rotate node (* delta-sign delta-angle))

                         match-distance?
                         (gpt/scale-from node distance-scale))]
      [(- (:x new-opposite) (:x opposite))
       (- (:y new-opposite) (:y opposite))])))

(defn move-handler-modifiers
  [content index prefix match-distance? match-angle? dx dy]

  (let [[cx cy] (upc/prefix->coords prefix)
        [op-idx op-prefix] (upc/opposite-index content index prefix)

        node (upc/handler->node content index prefix)
        handler (upc/handler->point content index prefix)
        opposite (upc/handler->point content op-idx op-prefix)

        [ocx ocy] (upc/prefix->coords op-prefix)
        [odx ody] (calculate-opposite-delta node handler opposite match-angle? match-distance? dx dy)

        hnv (if (some? handler)
              (gpt/to-vec node (-> handler (update :x + dx) (update :y + dy)))
              (gpt/point dx dy))]

    (-> {}
        (update index assoc cx dx cy dy)

        (cond-> (and (some? op-idx) (not= opposite node))
          (update op-idx assoc ocx odx ocy ody)

          (and (some? op-idx) (= opposite node) match-distance? match-angle?)
          (update op-idx assoc ocx (- (:x hnv)) ocy (- (:y hnv)))))))
