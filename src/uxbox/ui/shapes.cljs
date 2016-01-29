(ns uxbox.ui.shapes
  "A ui related implementation for uxbox.shapes ns."
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.ui.icons :as i]
            [uxbox.util.svg :as svg]
            [uxbox.util.matrix :as mtx]
            [uxbox.util.math :as mth]
            [uxbox.util.data :refer (remove-nil-vals)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attribute transformations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private +style-attrs+
  #{:fill :opacity :stroke :stroke-opacity :stroke-width :stroke-type})

(defn- transform-stroke-type
  [attrs]
  (if-let [type (:stroke-type attrs)]
    (let [value (case type
                  :dotted "1,1"
                  :dashed "10,10")]
      (-> attrs
          (assoc! :stroke-dasharray value)
          (dissoc! :stroke-type)))
    attrs))

(defn- extract-style-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (let [attrs (select-keys shape +style-attrs+)]
    (-> (transient attrs)
        (transform-stroke-type)
        (persistent!))))

(defn- make-debug-attrs
  [shape]
  (let [attrs (select-keys shape [:rotation :width :height :x :y])
        xf (map (fn [[x v]]
                    [(keyword (str "data-" (name x))) v]))]
      (into {} xf attrs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod sh/-render :builtin/icon
  [{:keys [data id] :as shape} _]
  (let [key (str id)
        rfm (svg/calculate-transform shape)
        attrs (merge {:id key :key key :transform rfm}
                     (extract-style-attrs shape)
                     (make-debug-attrs shape))]
    (html
     [:g attrs data])))

(defmethod sh/-render :builtin/line
  [{:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        props (select-keys shape [:x1 :x2 :y2 :y1])
        attrs (-> (extract-style-attrs shape)
                  (merge {:id key :key key})
                  (merge props))]
    (html
     [:line attrs])))


(defmethod sh/-render :builtin/circle
  [{:keys [id] :as shape}]
  (let [key (str id)
        props (select-keys shape [:cx :cy :rx :ry])
        attrs (-> (extract-style-attrs shape)
                  (merge {:id key :key key})
                  (merge props))]
    (html
     [:ellipse attrs])))

(defmethod sh/-render :builtin/rect
  [{:keys [id x1 y1 x2 y2] :as shape}]
  (let [key (str id)
        props (select-keys shape [:x :y :width :height])
        attrs (-> (extract-style-attrs shape)
                  (merge {:id key :key key})
                  (merge props))]
    (html
     [:rect attrs])))

;; FIXME: the impl should be more clear.

(defmethod sh/-render :builtin/group
  [{:keys [items id dx dy rotation] :as shape} factory]
  (letfn [(rotation-matrix []
            (let [shapes-by-id (get @st/state :shapes-by-id)
                  shapes (map #(get shapes-by-id %) items)
                  {:keys [x y width height]} (sh/outer-rect shapes)
                  center-x (+ x (/ width 2))
                  center-y (+ y (/ height 2))]
              (mtx/multiply (svg/translate-matrix center-x center-y)
                            (svg/rotation-matrix rotation)
                            (svg/translate-matrix (- center-x)
                                                  (- center-y)))))
          (translate-matrix []
            (svg/translate-matrix (or dx 0) (or dy 0)))

          (transform []
            (let [result (mtx/multiply (rotation-matrix)
                                       (translate-matrix))
                  result (flatten @result)]
              (->> (map #(nth result %) [0 3 1 4 2 5])
                   (str/join ",")
                   (str/format "matrix(%s)"))))]
    (let [key (str "group-" id)
          tfm (transform)
          attrs (merge {:id key :key key :transform tfm}
                       (make-debug-attrs shape))
          shapes-by-id (get @st/state :shapes-by-id)]
      (html
       [:g attrs
        (for [item (->> items
                        (map #(get shapes-by-id %))
                        (remove :hidden))]
          (-> (factory item)
              (rum/with-key (str (:id item)))))]))))

(defmethod sh/-render-svg :builtin/icon
  [{:keys [data id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}
        attrs (merge props
                     (extract-style-attrs shape))]
    (html
     [:svg attrs data])))
