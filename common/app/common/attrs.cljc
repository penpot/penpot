;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.common.attrs)

(defn get-attrs-multi
  ([shapes attrs] (get-attrs-multi shapes attrs = identity))
  ([shapes attrs eq-fn sel-fn]
   ;; Extract some attributes of a list of shapes.
   ;; For each attribute, if the value is the same in all shapes,
   ;; wll take this value. If there is any shape that is different,
   ;; the value of the attribute will be the keyword :multiple.
   ;;
   ;; If some shape has the value nil in any attribute, it's
   ;; considered a different value. If the shape does not contain
   ;; the attribute, it's ignored in the final result.
   ;;
   ;; Example:
   ;;   (def shapes [{:stroke-color "#ff0000"
   ;;                 :stroke-width 3
   ;;                 :fill-color "#0000ff"
   ;;                 :x 1000 :y 2000 :rx nil}
   ;;                {:stroke-width "#ff0000"
   ;;                 :stroke-width 5
   ;;                 :x 1500 :y 2000}])
   ;;
   ;;   (get-attrs-multi shapes [:stroke-color
   ;;                            :stroke-width
   ;;                            :fill-color
   ;;                            :rx
   ;;                            :ry])
   ;;   >>> {:stroke-color "#ff0000"
   ;;        :stroke-width :multiple
   ;;        :fill-color "#0000ff"
   ;;        :rx nil
   ;;        :ry nil}
   ;;
   (let [defined-shapes (filter some? shapes)

         combine-value (fn [v1 v2]
                         (cond
                           (and (= v1 :undefined) (= v2 :undefined)) :undefined
                           (= v1 :undefined) (if (= v2 :multiple) :multiple (sel-fn v2))
                           (= v2 :undefined) (if (= v1 :multiple) :multiple (sel-fn v1))
                           (or (= v1 :multiple) (= v2 :multiple)) :multiple
                           (eq-fn v1 v2) (sel-fn v1)
                           :else :multiple))

         combine-values (fn [attrs shape values]
                          (map #(combine-value (get shape % :undefined)
                                               (get values % :undefined)) attrs))

         select-attrs (fn [shape attrs]
                        (zipmap attrs (map #(get shape % :undefined) attrs)))

         reducer (fn [result shape]
                   (zipmap attrs (combine-values attrs shape result)))

         combined (reduce reducer
                          (select-attrs (first defined-shapes) attrs)
                          (rest defined-shapes))

         cleanup-value (fn [value]
                         (if (= value :undefined) nil value))

         cleanup (fn [result]
                   (->> attrs
                        (map #(get result %))
                        (zipmap attrs)
                        (filter #(not= (second %) :undefined))
                        (into {})))]

     (cleanup combined))))
