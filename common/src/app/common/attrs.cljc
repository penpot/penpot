;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.attrs
  (:refer-clojure :exclude [merge]))

;; Extract some attributes of a list of shapes.
;; For each attribute, if the value is the same in all shapes,
;; will take this value. If there is any shape that is different,
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

(defn get-attrs-multi
  ([objs attrs]
   (get-attrs-multi objs attrs = identity))

  ([objs attrs eqfn sel]

   (loop [attr (first attrs)
          attrs (rest attrs)
          result (transient {})]
     (if attr
       (let [value
             (loop [curr (first objs)
                    objs (rest objs)
                    value ::undefined]

               (if (and curr (not= value :multiple))
                 ;;
                 (let [new-val (get curr attr ::undefined)
                       value (cond
                               (= new-val ::undefined) value
                               (= new-val :multiple)   :multiple
                               (= value ::undefined)   (sel new-val)
                               (eqfn new-val value)    value
                               :else                   :multiple)]
                   (recur (first objs) (rest objs) value))
                 ;;
                 value))]
         (recur (first attrs)
                (rest attrs)
                (cond-> result
                  (not= value ::undefined)
                  (assoc! attr value))))

       (persistent! result)))))

(defn merge
  "Attrs specific merge function."
  [obj attrs]
  (reduce-kv (fn [obj k v]
               (if (nil? v)
                 (dissoc obj k)
                 (assoc obj k v)))
             obj
             attrs))
