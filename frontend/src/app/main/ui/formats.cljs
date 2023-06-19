;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.formats
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [cuerdas.core :as str]))

(defn format-percent
  ([value]
   (format-percent value nil))

  ([value {:keys [precision] :or {precision 2}}]
   (let [value (if (string? value) (d/parse-double value) value)]
     (when (d/num? value)
       (let [percent-val (mth/precision (* value 100) precision)]
         (dm/str percent-val "%"))))))

(defn format-number
  ([value]
   (format-number value nil))
  ([value {:keys [precision] :or {precision 2}}]
   (let [value (if (string? value) (d/parse-double value) value)]
     (when (d/num? value)
       (let [value (mth/precision value precision)]
         (dm/str value))))))

(defn format-pixels
  ([value]
   (format-pixels value nil))

  ([value {:keys [precision] :or {precision 2}}]
   (let [value (if (string? value) (d/parse-double value) value)]
     (when (d/num? value)
       (let [value (mth/precision value precision)]
         (dm/str value "px"))))))

(defn format-int
  [value]
  (let [value (if (string? value) (d/parse-double value) value)]
    (when (d/num? value)
      (let [value (mth/precision value 0)]
        (dm/str value)))))

(defn format-padding-margin-shorthand
  [values]
  ;; Values come in [p1 p2 p3 p4]
  (let [[p1 p2 p3 p4] values
        p1 (format-number p1)
        p2 (format-number p2)
        p3 (format-number p3)
        p4 (format-number p4)]
    (cond
      (= p1 p2 p3 p4)
      {:p1 p1}

      (= 4 (count (set [p1 p2 p3 p4])))
      {:p1 p1 :p2 p2 :p3 p3 :p4 p4}

      (and (= p1 p3) (= p2 p4))
      {:p1 p1 :p2 p2}

      (and (not= p1 p3) (= p2 p4))
      {:p1 p1 :p2 p2 :p3 p3}
      :else
      {:p1 p1 :p2 p2 :p3 p3 :p4 p4})))

(defn format-size [type value shape]
  (let [sizing (if (= type :width)
                 (:layout-item-h-sizing shape)
                 (:layout-item-v-sizing shape))]
    (cond
      (= sizing :fill) "100%"
      (= sizing :auto) "auto"
      (number? value)  (format-pixels value)
      :else            value)))

(defn format-padding
  [padding-values type]
  (let [new-padding (if (= :margin type)
                      {:m1 0 :m2 0 :m3 0 :m4 0}
                      {:p1 0 :p2 0 :p3 0 :p4 0})
        merged-padding (merge new-padding padding-values)
        short-hand (format-padding-margin-shorthand (vals merged-padding))
        parsed-values (map #(str/fmt "%spx" %) (vals short-hand))]
    (str/join " " parsed-values)))

(defn format-margin
  [margin-values]
  (format-padding margin-values :margin))

(defn format-gap
  [gap-values]
  (let [row-gap (:row-gap gap-values)
        column-gap (:column-gap gap-values)]
    (if (= row-gap column-gap)
      (str/fmt "%spx" (format-number row-gap))
      (str/fmt "%spx %spx" (format-number row-gap) (format-number column-gap)))))
