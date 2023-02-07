;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.layout
  (:require
   [app.common.data.macros :as dm]
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; :layout                 ;; :flex, :grid in the future
;; :layout-flex-dir        ;; :row, :row-reverse, :column, :column-reverse
;; :layout-gap-type        ;; :simple, :multiple
;; :layout-gap             ;; {:row-gap number , :column-gap number}
;; :layout-align-items     ;; :start :end :center :stretch
;; :layout-justify-content ;; :start :center :end :space-between :space-around
;; :layout-align-content   ;; :start :center :end :space-between :space-around :stretch (by default)
;; :layout-wrap-type       ;; :wrap, :nowrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative

;; ITEMS
;; :layout-item-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
;; :layout-item-margin-type ;; :simple :multiple
;; :layout-item-h-sizing    ;; :fill :fix :auto
;; :layout-item-v-sizing    ;; :fill :fix :auto
;; :layout-item-max-h       ;; num
;; :layout-item-min-h       ;; num
;; :layout-item-max-w       ;; num
;; :layout-item-min-w       ;; num

(s/def ::layout  #{:flex :grid})
(s/def ::layout-flex-dir #{:row :reverse-row :row-reverse :column :reverse-column :column-reverse}) ;;TODO remove reverse-column and reverse-row after script
(s/def ::layout-gap-type #{:simple :multiple})
(s/def ::layout-gap ::us/safe-number)
(s/def ::layout-align-items #{:start :end :center :stretch})
(s/def ::layout-align-content #{:start :end :center :space-between :space-around :stretch})
(s/def ::layout-justify-content #{:start :center :end :space-between :space-around})
(s/def ::layout-wrap-type #{:wrap :nowrap :no-wrap}) ;;TODO remove no-wrap after script
(s/def ::layout-padding-type #{:simple :multiple})

(s/def ::p1 ::us/safe-number)
(s/def ::p2 ::us/safe-number)
(s/def ::p3 ::us/safe-number)
(s/def ::p4 ::us/safe-number)

(s/def ::layout-padding
  (s/keys :opt-un [::p1 ::p2 ::p3 ::p4]))

(s/def ::row-gap ::us/safe-number)
(s/def ::column-gap ::us/safe-number)

(s/def ::layout-gap
  (s/keys :opt-un [::row-gap ::column-gap]))

(s/def ::layout-container-props
  (s/keys :opt-un [::layout
                   ::layout-flex-dir
                   ::layout-gap
                   ::layout-gap-type
                   ::layout-wrap-type
                   ::layout-padding-type
                   ::layout-padding
                   ::layout-justify-content
                   ::layout-align-items
                   ::layout-align-content]))

(s/def ::m1 ::us/safe-number)
(s/def ::m2 ::us/safe-number)
(s/def ::m3 ::us/safe-number)
(s/def ::m4 ::us/safe-number)

(s/def ::layout-item-margin (s/keys :opt-un [::m1 ::m2 ::m3 ::m4]))

(s/def ::layout-item-margin-type #{:simple :multiple})
(s/def ::layout-item-h-sizing #{:fill :fix :auto})
(s/def ::layout-item-v-sizing #{:fill :fix :auto})
(s/def ::layout-item-align-self #{:start :end :center :stretch})
(s/def ::layout-item-max-h ::us/safe-number)
(s/def ::layout-item-min-h ::us/safe-number)
(s/def ::layout-item-max-w ::us/safe-number)
(s/def ::layout-item-min-w ::us/safe-number)

(s/def ::layout-child-props
  (s/keys :opt-un [::layout-item-margin
                   ::layout-item-margin-type
                   ::layout-item-h-sizing
                   ::layout-item-v-sizing
                   ::layout-item-max-h
                   ::layout-item-min-h
                   ::layout-item-max-w
                   ::layout-item-min-w
                   ::layout-item-align-self]))

(defn layout?
  ([objects id]
   (layout? (get objects id)))
  ([shape]
   (and (= :frame (:type shape)) (= :flex (:layout shape)))))

(defn layout-immediate-child? [objects shape]
  (let [parent-id (:parent-id shape)
        parent (get objects parent-id)]
    (layout? parent)))

(defn layout-immediate-child-id? [objects id]
  (let [parent-id (dm/get-in objects [id :parent-id])
        parent (get objects parent-id)]
    (layout? parent)))

(defn layout-descent? [objects shape]
  (let [frame-id (:frame-id shape)
        frame (get objects frame-id)]
    (layout? frame)))

(defn inside-layout?
  "Check if the shape is inside a layout"
  [objects shape]

  (loop [current-id (:id shape)]
    (let [current (get objects current-id)]
      (cond
        (or (nil? current) (= current-id (:parent-id current)))
        false

        (= :frame (:type current))
        (:layout current)

        :else
        (recur (:parent-id current))))))

(defn wrap? [{:keys [layout-wrap-type]}]
  (= layout-wrap-type :wrap))

(defn fill-width?
  ([objects id]
   (= :fill (dm/get-in objects [id :layout-item-h-sizing])))
  ([child]
   (= :fill (:layout-item-h-sizing child))))

(defn fill-height?
  ([objects id]
   (= :fill (dm/get-in objects [id :layout-item-v-sizing])))
  ([child]
   (= :fill (:layout-item-v-sizing child))))

(defn auto-width?
  ([objects id]
   (= :auto (dm/get-in objects [id :layout-item-h-sizing])))
  ([child]
   (= :auto (:layout-item-h-sizing child))))

(defn auto-height?
  ([objects id]
   (= :auto (dm/get-in objects [id :layout-item-v-sizing])))
  ([child]
   (= :auto (:layout-item-v-sizing child))))

(defn col?
  ([objects id]
   (col? (get objects id)))
  ([{:keys [layout-flex-dir]}]
   (or (= :column layout-flex-dir) (= :column-reverse layout-flex-dir))))

(defn row?
  ([objects id]
   (row? (get objects id)))
  ([{:keys [layout-flex-dir]}]
   (or (= :row layout-flex-dir) (= :row-reverse layout-flex-dir))))

(defn gaps
  [{:keys [layout-gap]}]
  (let [layout-gap-row (or (-> layout-gap :row-gap) 0)
        layout-gap-col (or (-> layout-gap :column-gap) 0)]
    [layout-gap-row layout-gap-col]))

(defn child-min-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-min-w child)))
    (max 0 (:layout-item-min-w child))
    0))

(defn child-max-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-item-max-w child)))
    (max 0 (:layout-item-max-w child))
    ##Inf))

(defn child-min-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-min-h child)))
    (max 0 (:layout-item-min-h child))
    0))

(defn child-max-height
  [child]
  (if (and (fill-height? child)
           (some? (:layout-item-max-h child)))
    (max 0 (:layout-item-max-h child))
    ##Inf))

(defn child-margins
  [{{:keys [m1 m2 m3 m4]} :layout-item-margin :keys [layout-item-margin-type]}]
  (let [m1 (or m1 0)
        m2 (or m2 0)
        m3 (or m3 0)
        m4 (or m4 0)]
    (if (= layout-item-margin-type :multiple)
      [m1 m2 m3 m4]
      [m1 m2 m1 m2])))

(defn child-height-margin
  [child]
  (let [[top _ bottom _] (child-margins child)]
    (+ top bottom)))

(defn child-width-margin
  [child]
  (let [[_ right _ left] (child-margins child)]
    (+ right left)))

(defn h-start?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :start))
      (and (row? shape)
           (= layout-justify-content :start))))

(defn h-center?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :center))
      (and (row? shape)
           (= layout-justify-content :center))))

(defn h-end?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (col? shape)
           (= layout-align-items :end))
      (and (row? shape)
           (= layout-justify-content :end))))

(defn v-start?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :start))
      (and (col? shape)
           (= layout-justify-content :start))))

(defn v-center?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :center))
      (and (col? shape)
           (= layout-justify-content :center))))

(defn v-end?
  [{:keys [layout-align-items layout-justify-content] :as shape}]
  (or (and (row? shape)
           (= layout-align-items :end))
      (and (col? shape)
           (= layout-justify-content :end))))

(defn content-start?
  [{:keys [layout-align-content]}]
  (= :start layout-align-content))

(defn content-center?
  [{:keys [layout-align-content]}]
  (= :center layout-align-content))

(defn content-end?
  [{:keys [layout-align-content]}]
  (= :end layout-align-content))

(defn content-between?
  [{:keys [layout-align-content]}]
  (= :space-between layout-align-content))

(defn content-around?
  [{:keys [layout-align-content]}]
  (= :space-around layout-align-content))

(defn content-stretch?
  [{:keys [layout-align-content]}]
  (or (= :stretch layout-align-content)
      (nil? layout-align-content)))

(defn align-items-center?
  [{:keys [layout-align-items]}]
  (= layout-align-items :center))

(defn align-items-start?
  [{:keys [layout-align-items]}]
  (= layout-align-items :start))

(defn align-items-end?
  [{:keys [layout-align-items]}]
  (= layout-align-items :end))

(defn align-items-stretch?
  [{:keys [layout-align-items]}]
  (= layout-align-items :stretch))

(defn reverse?
  [{:keys [layout-flex-dir]}]
  (or (= :row-reverse layout-flex-dir)
      (= :column-reverse layout-flex-dir)))

(defn space-between?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-between))

(defn space-around?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-around))

(defn align-self-start? [{:keys [layout-item-align-self]}]
  (= :start layout-item-align-self))

(defn align-self-end? [{:keys [layout-item-align-self]}]
  (= :end layout-item-align-self))

(defn align-self-center? [{:keys [layout-item-align-self]}]
  (= :center layout-item-align-self))

(defn align-self-stretch? [{:keys [layout-item-align-self]}]
  (= :stretch layout-item-align-self))

(defn change-h-sizing?
  [frame-id objects children-ids]
  (and (layout? objects frame-id)
       (auto-width? objects frame-id)
       (or (and (col? objects frame-id)
                (every? (partial fill-width? objects) children-ids))
           (and (row? objects frame-id)
                (some (partial fill-width? objects) children-ids)))))

(defn change-v-sizing?
  [frame-id objects children-ids]
  (and (layout? objects frame-id)
       (auto-height? objects frame-id)
       (or (and (col? objects frame-id)
                (some (partial fill-height? objects) children-ids))
           (and (row? objects frame-id)
                (every? (partial fill-height? objects) children-ids)))))

