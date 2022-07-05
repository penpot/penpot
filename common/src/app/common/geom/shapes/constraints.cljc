;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.geom.shapes.constraints
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.rect :as gre]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]))

;; Auxiliary methods to work in an specifica axis
(defn get-delta-start [axis rect tr-rect]
  (if (= :x axis)
    (- (:x1 tr-rect) (:x1 rect))
    (- (:y1 tr-rect) (:y1 rect))))

(defn get-delta-end [axis rect tr-rect]
  (if (= :x axis)
    (- (:x2 tr-rect) (:x2 rect))
    (- (:y2 tr-rect) (:y2 rect))))

(defn get-delta-size [axis rect tr-rect]
  (if (= :x axis)
    (- (:width tr-rect) (:width rect))
    (- (:height tr-rect) (:height rect))))

(defn get-delta-center [axis center tr-center]
  (if (= :x axis)
    (- (:x tr-center) (:x center))
    (- (:y tr-center) (:y center))))

(defn get-displacement
  ([axis delta]
   (get-displacement axis delta 0 0))

  ([axis delta init-x init-y]
   (if (= :x axis)
     (gpt/point (+ init-x delta) init-y)
     (gpt/point init-x (+ init-y delta)))))

(defn get-scale [axis scale]
  (if (= :x axis)
    (gpt/point scale 1)
    (gpt/point 1 scale)))

(defn get-size [axis rect]
  (if (= :x axis)
    (:width rect)
    (:height rect)))

;; Constraint function definitions

(defmulti constraint-modifier (fn [type & _] type))

(defmethod constraint-modifier :start
  [_ axis parent _ _ transformed-parent-rect]

  (let [parent-rect (:selrect parent)
        delta-start (get-delta-start axis parent-rect transformed-parent-rect)]
    (if-not (mth/almost-zero? delta-start)
      {:displacement (get-displacement axis delta-start)}
      {})))

(defmethod constraint-modifier :end
  [_ axis parent _ _ transformed-parent-rect]
  (let [parent-rect (:selrect parent)
        delta-end (get-delta-end axis parent-rect transformed-parent-rect)]
    (if-not (mth/almost-zero? delta-end)
      {:displacement (get-displacement axis delta-end)}
      {})))

(defmethod constraint-modifier :fixed
  [_ axis parent child _ transformed-parent-rect]
  (let [parent-rect (:selrect parent)
        child-rect (gre/points->rect (:points child))

        delta-start  (get-delta-start axis parent-rect transformed-parent-rect)
        delta-size   (get-delta-size axis parent-rect transformed-parent-rect)
        child-size   (get-size axis child-rect)]
    (if (or (not (mth/almost-zero? delta-start))
            (not (mth/almost-zero? delta-size)))

      {:displacement (get-displacement axis delta-start)
       :resize-origin (get-displacement axis delta-start (:x child-rect) (:y child-rect))
       :resize-vector (get-scale axis (/ (+ child-size delta-size) child-size))}
      {})))

(defmethod constraint-modifier :center
  [_ axis parent _ _ transformed-parent-rect]
  (let [parent-rect (:selrect parent)
        parent-center (gco/center-rect parent-rect)
        transformed-parent-center (gco/center-rect transformed-parent-rect)
        delta-center (get-delta-center axis parent-center transformed-parent-center)]
    (if-not (mth/almost-zero? delta-center)
      {:displacement (get-displacement axis delta-center)}
      {})))

(defmethod constraint-modifier :scale
  [_ axis _ _ modifiers _]
  (let [{:keys [resize-vector resize-vector-2 displacement]} modifiers]
    (cond-> {}
      (and (some? resize-vector)
           (not= (axis resize-vector) 1))
      (assoc :resize-origin (:resize-origin modifiers)
             :resize-vector (if (= :x axis)
                              (gpt/point (:x resize-vector) 1)
                              (gpt/point 1 (:y resize-vector))))

      (and (= :y axis) (some? resize-vector-2)
           (not (mth/close? (:y resize-vector-2) 1)))
      (assoc :resize-origin (:resize-origin-2 modifiers)
             :resize-vector (gpt/point 1 (:y resize-vector-2)))

      (some? displacement)
      (assoc :displacement
             (get-displacement axis (-> (gpt/point 0 0)
                                        (gpt/transform displacement)
                                        (gpt/transform (:resize-transform-inverse modifiers (gmt/matrix)))
                                        axis))))))

(defmethod constraint-modifier :default [_ _ _ _ _]
  {})

(def const->type+axis
  {:left :start
   :top :start
   :right :end
   :bottom :end
   :leftright :fixed
   :topbottom :fixed
   :center :center
   :scale :scale})

(defn default-constraints-h
  [shape]
  (if (= (:parent-id shape) uuid/zero)
    nil
    (if (= (:parent-id shape) (:frame-id shape))
      :left
      :scale)))

(defn default-constraints-v
  [shape]
  (if (= (:parent-id shape) uuid/zero)
    nil
    (if (= (:parent-id shape) (:frame-id shape))
      :top
      :scale)))

(defn clean-modifiers
  "Remove redundant modifiers"
  [{:keys [displacement resize-vector resize-vector-2] :as modifiers}]

  (cond-> modifiers
    ;; Displacement with value 0. We don't move in any direction
    (and (some? displacement)
         (mth/almost-zero? (:e displacement))
         (mth/almost-zero? (:f displacement)))
    (dissoc :displacement)

    ;; Resize with value very close to 1 means no resize
    (and (some? resize-vector)
         (mth/almost-zero? (- 1.0 (:x resize-vector)))
         (mth/almost-zero? (- 1.0 (:y resize-vector))))
    (dissoc :resize-origin :resize-vector)

    (and (some? resize-vector)
         (mth/almost-zero? (- 1.0 (:x resize-vector-2)))
         (mth/almost-zero? (- 1.0 (:y resize-vector-2))))
    (dissoc :resize-origin-2 :resize-vector-2)))

(defn calc-child-modifiers
  [parent child modifiers ignore-constraints transformed-parent-rect]

  (if (and (nil? (:resize-vector modifiers))
           (nil? (:resize-vector-2 modifiers)))
    ;; If we don't have a resize modifier we return the same modifiers
    modifiers
    (let [constraints-h
          (if-not ignore-constraints
            (:constraints-h child (default-constraints-h child))
            :scale)

          constraints-v
          (if-not ignore-constraints
            (:constraints-v child (default-constraints-v child))
            :scale)

          modifiers-h (constraint-modifier (constraints-h const->type+axis) :x parent child modifiers transformed-parent-rect)
          modifiers-v (constraint-modifier (constraints-v const->type+axis) :y parent child modifiers transformed-parent-rect)]

      ;; Build final child modifiers. Apply transform again to the result, to get the
      ;; real modifiers that need to be applied to the child, including rotation as needed.
      (cond-> {}
        (some? (:displacement-after modifiers))
        (assoc :displacement-after (:displacement-after modifiers))

        (or (contains? modifiers-h :displacement)
            (contains? modifiers-v :displacement))
        (assoc :displacement (cond-> (gpt/point (get-in modifiers-h [:displacement :x] 0)
                                                (get-in modifiers-v [:displacement :y] 0))
                               (some? (:resize-transform modifiers))
                               (gpt/transform (:resize-transform modifiers))

                               :always
                               (gmt/translate-matrix)))

        (:resize-vector modifiers-h)
        (assoc :resize-origin (:resize-origin modifiers-h)
               :resize-vector (gpt/point (get-in modifiers-h [:resize-vector :x] 1)
                                         (get-in modifiers-h [:resize-vector :y] 1)))

        (:resize-vector modifiers-v)
        (assoc :resize-origin-2 (:resize-origin modifiers-v)
               :resize-vector-2 (gpt/point (get-in modifiers-v [:resize-vector :x] 1)
                                           (get-in modifiers-v [:resize-vector :y] 1)))

        (:resize-transform modifiers)
        (assoc :resize-transform (:resize-transform modifiers)
               :resize-transform-inverse (:resize-transform-inverse modifiers))

        :always
        (clean-modifiers)))))
