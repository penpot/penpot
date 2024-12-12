;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.attrs
  (:require
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.text :as txt]))

(defn- get-attr
  [obj attr]
  (if (= (get obj attr) :multiple)
    :multiple
    (cond
      ;; For rotated or stretched shapes, the origin point we show in the menu
      ;; is not the (:x :y) shape attribute, but the top left coordinate of the
      ;; wrapping rectangle (see measures.cljs). As the :points attribute cannot
      ;; be merged for several objects, we calculate the origin point in two fake
      ;; attributes to be used in the measures menu.
      (#{:ox :oy} attr)
      (if-let [value (get obj attr)]
        value
        (if-let [points (:points obj)]
          (if (not= points :multiple)
            ;; FIXME: consider using gsh/shape->rect ??
            (let [rect (gsh/shapes->rect [obj])]
              (if (= attr :ox) (:x rect) (:y rect)))
            :multiple)
          (get obj attr ::unset)))

      ;; Not all shapes have width and height (e.g. paths), so we extract
      ;; them from the :selrect attribute.
      (#{:width :height} attr)
      (if-let [value (get obj attr)]
        value
        (if-let [selrect (:selrect obj)]
          (if (not= selrect :multiple)
            (get (:selrect obj) attr)
            :multiple)
          (get obj attr ::unset)))

      :else
      (get obj attr ::unset))))

(defn- default-equal
  [val1 val2]
  (if (and (number? val1) (number? val2))
    (mth/close? val1 val2)
    (= val1 val2)))

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
;;                 :x 1000 :y 2000}
;;                {:stroke-width "#ff0000"
;;                 :stroke-width 5
;;                 :x 1500 :y 2000}])
;;
;;   (get-attrs-multi shapes [:stroke-color
;;                            :stroke-width
;;                            :fill-color
;;                            :r1
;;                            :r2
;;                            :r3
;;                            :r4])
;;   >>> {:stroke-color "#ff0000"
;;        :stroke-width :multiple
;;        :fill-color "#0000ff"
;;        :r1 nil
;;        :r2 nil
;;        :r3 nil
;;        :r4 nil}
;;
(defn get-attrs-multi
  ([objs attrs]
   (get-attrs-multi objs attrs default-equal identity))

  ([objs attrs eqfn sel]
   (loop [attr (first attrs)
          attrs (rest attrs)
          result (transient {})]
     (if attr
       (let [value
             (loop [curr (first objs)
                    objs (rest objs)
                    value ::unset]

               (if (and curr (not= value :multiple))
                 (let [new-val (get-attr curr attr)
                       value (cond
                               (= new-val ::unset)   value
                               (= new-val :multiple) :multiple
                               (= value ::unset)     (sel new-val)
                               (eqfn new-val value)  value
                               :else                 :multiple)]
                   (recur (first objs) (rest objs) value))

                 value))]

         (recur (first attrs)
                (rest attrs)
                (cond-> result
                  (not= value ::unset)
                  (assoc! attr value))))

       (persistent! result)))))

(defn get-text-attrs-multi
  "Gets the multi attributes for a text shape. Splits the content by type and gets the attributes depending
  on the node type"
  [{:keys [content]} defaults attrs]
  (let [root-attrs (->> attrs (filter (set txt/root-attrs)))
        paragraph-attrs (->> attrs (filter (set txt/paragraph-attrs)))
        text-node-attrs (->> attrs (filter (set txt/text-node-attrs)))]
    (merge
     defaults
     (get-attrs-multi (->> (txt/node-seq txt/is-root-node? content)) root-attrs)
     (get-attrs-multi (->> (txt/node-seq txt/is-paragraph-node? content)) paragraph-attrs)
     (get-attrs-multi (->> (txt/node-seq txt/is-text-node? content)) text-node-attrs))))
