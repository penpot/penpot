;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.shape.layout
  (:require
   [app.common.spec :as us]
   [clojure.spec.alpha :as s]))

;; :layout                 ;; :flex, :grid in the future
;; :layout-flex-dir        ;; :row, :reverse-row, :column, :reverse-column
;; :layout-gap-type        ;; :simple, :multiple
;; :layout-gap             ;; {:row-gap number , :column-gap number}
;; :layout-align-items     ;; :start :end :center :strech
;; :layout-justify-content ;; :start :center :end :space-between :space-around
;; :layout-align-content   ;; :start :center :end :space-between :space-around :strech (by default)
;; :layout-wrap-type       ;; :wrap, :no-wrap
;; :layout-padding-type    ;; :simple, :multiple
;; :layout-padding         ;; {:p1 num :p2 num :p3 num :p4 num} number could be negative

;; ITEMS
;; :layout-margin      ;; {:m1 0 :m2 0 :m3 0 :m4 0}
;; :layout-margin-type ;; :simple :multiple
;; :layout-h-behavior  ;; :fill :fix :auto
;; :layout-v-behavior  ;; :fill :fix :auto
;; :layout-max-h       ;; num
;; :layout-min-h       ;; num
;; :layout-max-w       ;; num
;; :layout-min-w

(s/def ::layout  #{:flex :grid})
(s/def ::layout-flex-dir #{:row :reverse-row :column :reverse-column})
(s/def ::layout-gap-type #{:simple :multiple})
(s/def ::layout-gap ::us/safe-number)
(s/def ::layout-align-items #{:start :end :center :strech})
(s/def ::layout-align-content #{:start :end :center :space-between :space-around :strech})
(s/def ::layout-justify-content #{:start :center :end :space-between :space-around})
(s/def ::layout-wrap-type #{:wrap :no-wrap})
(s/def ::layout-padding-type #{:simple :multiple})

(s/def ::p1 ::us/safe-number)
(s/def ::p2 ::us/safe-number)
(s/def ::p3 ::us/safe-number)
(s/def ::p4 ::us/safe-number)

(s/def ::layout-padding
  (s/keys :req-un [::p1]
          :opt-un [::p2 ::p3 ::p4]))

(s/def ::row-gap ::us/safe-number)
(s/def ::column-gap ::us/safe-number)
(s/def ::layout-type #{:flex :grid})

(s/def ::layout-gap
  (s/keys :req-un [::row-gap ::column-gap]))

(s/def ::layout-container-props
  (s/keys :opt-un [::layout
                   ::layout-flex-dir
                   ::layout-gap
                   ::layout-gap-type
                   ::layout-type
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

(s/def ::layout-margin (s/keys :req-un [::m1]
                               :opt-un [::m2 ::m3 ::m4]))

(s/def ::layout-margin-type #{:simple :multiple})
(s/def ::layout-h-behavior #{:fill :fix :auto})
(s/def ::layout-v-behavior #{:fill :fix :auto})
(s/def ::layout-align-self #{:start :end :center :strech :baseline})
(s/def ::layout-max-h ::us/safe-number)
(s/def ::layout-min-h ::us/safe-number)
(s/def ::layout-max-w ::us/safe-number)
(s/def ::layout-min-w ::us/safe-number)

(s/def ::layout-child-props
  (s/keys :opt-un [::layout-margin
                   ::layout-margin-type
                   ::layout-h-behavior
                   ::layout-v-behavior
                   ::layout-max-h
                   ::layout-min-h
                   ::layout-max-w
                   ::layout-min-w
                   ::layout-align-self]))


(defn wrap? [{:keys [layout-wrap-type]}]
  (= layout-wrap-type :wrap))

(defn fill-width? [child]
  (= :fill (:layout-h-behavior child)))

(defn fill-height? [child]
  (= :fill (:layout-v-behavior child)))

(defn col?
  [{:keys [layout-flex-dir]}]
  (or (= :column layout-flex-dir) (= :reverse-column layout-flex-dir)))

(defn row?
  [{:keys [layout-flex-dir]}]
  (or (= :row layout-flex-dir) (= :reverse-row layout-flex-dir)))

(defn gaps
  [{:keys [layout-gap layout-gap-type]}]
  (let [layout-gap-row (or (-> layout-gap :row-gap) 0)
        layout-gap-col (if (= layout-gap-type :simple)
                         layout-gap-row
                         (or (-> layout-gap :column-gap) 0))]
    [layout-gap-row layout-gap-col]))

(defn child-min-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-min-h child)))
    (max 0 (:layout-min-h child))
    0))

(defn child-max-width
  [child]
  (if (and (fill-width? child)
           (some? (:layout-min-h child)))
    (max 0 (:layout-min-h child))
    0))

(defn child-min-height
  [child]
  (if (and (fill-width? child)
           (some? (:layout-min-v child)))
    (max 0 (:layout-min-v child))
    0))

(defn child-max-height
  [child]
  (if (and (fill-width? child)
           (some? (:layout-min-v child)))
    (max 0 (:layout-min-v child))
    0))

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
(defn reverse?
  [{:keys [layout-flex-dir]}]
  (or (= :reverse-row layout-flex-dir)
      (= :reverse-column layout-flex-dir)))

(defn space-between?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-between))

(defn space-around?
  [{:keys [layout-justify-content]}]
  (= layout-justify-content :space-around))
