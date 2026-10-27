;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.geometry
  "Expected geometry from authored inputs and parent frames. Only primitive
   constraint and transform math is shared with production."
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]))

(defn- orientation-matrix
  "Resize uses constraints and dimensions. Parent placement supplies axis
   directions, without applying residual minimum-size scale a second time."
  [shape]
  (let [{:keys [a b c d]} (or (:transform shape) (gmt/matrix))
        horizontal (gpt/unit (gpt/point a b))
        vertical (gpt/unit (gpt/point c d))]
    (gmt/matrix (:x horizontal) (:y horizontal) (:x vertical) (:y vertical) 0 0)))

(defn child-placement
  "Place a main child against its expected copy parent. Only lower-level
   constraint and matrix math is shared with production, not sync code."
  [main main-parent parent objects]
  (let [resized (if (and (mth/close? (:width main-parent) (:width parent) 0.01)
                         (mth/close? (:height main-parent) (:height parent) 0.01))
                  objects
                  (let [tree (gm/set-objects-modifiers
                              {(:id main-parent)
                               {:modifiers (ctm/change-size main-parent (:width parent) (:height parent))}}
                              objects)]
                    (reduce-kv (fn [objects id {:keys [modifiers]}]
                                 (update objects id gsh/transform-shape modifiers)) objects tree)))
        main (get resized (:id main))
        main-parent (get resized (:id main-parent))
        center (gsh/shape->center main-parent)
        turn (gmt/multiply (orientation-matrix parent)
                           (gmt/inverse (orientation-matrix main-parent)))
        placed (if (gmt/unit? turn) main
                   (gsh/apply-transform main (gmt/transform-in center turn)))
        ;; Flip flags follow the parent's composition history. A single
        ;; large turn can toggle both flags where several small turns do not.
        flipped? (fn [key]
                   (odd? (count (filter identity [(get main key) (get main-parent key)
                                                  (get parent key)]))))]
    (-> placed
        (gsh/move (gpt/subtract (gsh/shape->center parent) center))
        (assoc :rotation (mod (+ (or (:rotation main) 0)
                                 (- (or (:rotation parent) 0) (or (:rotation main-parent) 0))) 360)
               :flip-x (flipped? :flip-x)
               :flip-y (flipped? :flip-y)))))
