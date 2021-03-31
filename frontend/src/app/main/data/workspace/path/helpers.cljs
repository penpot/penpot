;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.path.helpers
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.main.data.workspace.path.state :refer [get-path]]
   [app.main.data.workspace.path.common :as common]
   [app.main.streams :as ms]
   [app.util.geom.path :as ugp]
   [potok.core :as ptk]))

;; CONSTANTS
(defonce enter-keycode 13)

(defn end-path-event? [{:keys [type shift] :as event}]
  (or (= (ptk/type event) ::common/finish-path)
      (= (ptk/type event) :esc-pressed)
      (= event :interrupt) ;; ESC
      (and (ms/mouse-double-click? event))))

(defn content->points+selrect
  "Given the content of a shape, calculate its points and selrect"
  [shape content]
  (let [transform (:transform shape (gmt/matrix))
        transform-inverse (:transform-inverse shape (gmt/matrix))
        center (gsh/center-shape shape)
        base-content (gsh/transform-content
                      content
                      (gmt/transform-in center transform-inverse))

        ;; Calculates the new selrect with points given the old center
        points (-> (gsh/content->selrect base-content)
                   (gsh/rect->points)
                   (gsh/transform-points center (:transform shape (gmt/matrix))))

        points-center (gsh/center-points points)

        ;; Points is now the selrect but the center is different so we can create the selrect
        ;; through points
        selrect (-> points
                    (gsh/transform-points points-center (:transform-inverse shape (gmt/matrix)))
                    (gsh/points->selrect))]
    [points selrect]))

(defn update-selrect
  "Updates the selrect and points for a path"
  [shape]
  (if (= (:rotation shape 0) 0)
    (let [content (:content shape)
          selrect (gsh/content->selrect content)
          points (gsh/rect->points selrect)]
      (assoc shape :points points :selrect selrect))

    (let [content (:content shape)
          [points selrect] (content->points+selrect shape content)]
      (assoc shape :points points :selrect selrect))))


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
  (let [last-command (-> shape :content last :command)
        add-line?   (and prev-point (not prev-handler) (not= last-command :close-path))
        add-curve?  (and prev-point prev-handler (not= last-command :close-path))]
    (cond
      add-line?   {:command :line-to
                   :params position}
      add-curve?  {:command :curve-to
                   :params (ugp/make-curve-params position prev-handler)}
      :else       {:command :move-to
                   :params position})))

(defn append-node
  "Creates a new node in the path. Usualy used when drawing."
  [shape position prev-point prev-handler]
  (let [command (next-node shape position prev-point prev-handler)]
    (-> shape
        (update :content (fnil conj []) command)
        (update-selrect))))

(defn move-handler-modifiers
  [content index prefix match-opposite? dx dy]
  (let [[cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
        [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
        opposite-index (ugp/opposite-index content index prefix)]

    (cond-> {}
      :always
      (update index assoc cx dx cy dy)

      (and match-opposite? opposite-index)
      (update opposite-index assoc ocx (- dx) ocy (- dy)))))
