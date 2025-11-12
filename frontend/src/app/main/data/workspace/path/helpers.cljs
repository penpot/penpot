;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.helpers
  (:require
   [app.common.geom.point :as gpt]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.common.types.path.helpers :as path.helpers]
   [app.common.types.path.segment :as path.segment]))

(defn append-node
  "Creates a new node in the path. Usually used when drawing."
  [shape position prev-point prev-handler]
  (let [segment (path.segment/next-node (:content shape) position prev-point prev-handler)]
    (-> shape
        (update :content path.segment/append-segment segment)
        (path/update-geometry))))

(defn angle-points [common p1 p2]
  (mth/abs
   (gpt/angle-with-other
    (gpt/to-vec common p1)
    (gpt/to-vec common p2))))

(defn- calculate-opposite-delta [node handler opposite match-angle? match-distance? dx dy]
  (when (and (some? handler) (some? opposite))
    (let [;; To match the angle, the angle should be matching (angle between points 180deg)
          angle-handlers (angle-points node handler opposite)

          match-angle? (and match-angle? (<= (mth/abs (- 180 angle-handlers)) 0.1))

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

  (let [[cx cy] (path.helpers/prefix->coords prefix)
        [op-idx op-prefix] (path.segment/opposite-index content index prefix)

        node (path.segment/handler->node content index prefix)
        handler (path.segment/get-handler-point content index prefix)
        opposite (path.segment/get-handler-point content op-idx op-prefix)

        [ocx ocy] (path.helpers/prefix->coords op-prefix)
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
