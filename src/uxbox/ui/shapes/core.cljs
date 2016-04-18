(ns uxbox.ui.shapes.core
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [uxbox.state :as st]
            [uxbox.ui.mixins :as mx]
            [uxbox.util.geom :as geom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Common constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +circle-props+
  {:r 5
   :style {:fillOpacity "0.5"
           :strokeWidth "1px"
           :vectorEffect "non-scaling-stroke"}
   :fill "#333"
   :stroke "#333"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dispatch-by-type
  [shape & params]
  (:type shape))

(defmulti render-component
  (fn [own shape] (:type shape))
  :hierarchy #'geom/+hierarchy+)

(defmulti render-shape
  dispatch-by-type
  :hierarchy #'geom/+hierarchy+)

(defmulti render-shape-svg
  dispatch-by-type
  :hierarchy #'geom/+hierarchy+)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const selected-shapes-l
  (-> (l/in [:workspace :selected])
      (l/focus-atom st/state)))

(def ^:const drawing-state-l
  (-> (l/in [:workspace :drawing])
      (l/focus-atom st/state)))

(defn- focus-shape
  [id]
  (as-> (l/in [:shapes-by-id id]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- shape-render
  [own id]
  (let [shape-l (focus-shape id)
        shape (rum/react shape-l)]
    (when-not (:hidden shape)
      (render-component own shape))))

(def ^:const shape
  (mx/component
   {:render shape-render
    :name "shape"
    :mixins [(mx/local) mx/static rum/reactive]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attribute transformations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static ^:private +style-attrs+
  #{:fill :fill-opacity :opacity :stroke :stroke-opacity
    :stroke-width :stroke-type :rx :ry})

(defn- transform-stroke-type
  [attrs]
  (if-let [type (:stroke-type attrs)]
    (let [value (case type
                  :mixed "5,5,1,5"
                  :dotted "5,5"
                  :dashed "10,10"
                  nil)]
      (if value
        (-> attrs
            (assoc! :stroke-dasharray value)
            (dissoc! :stroke-type))
        (dissoc! attrs :stroke-type)))
    attrs))

(defn- transform-stroke-attrs
  [attrs]
  (if (= (:stroke-type attrs :none) :none)
    (dissoc! attrs :stroke-type :stroke-width :stroke-opacity :stroke)
    (transform-stroke-type attrs)))

(defn- extract-style-attrs
  "Extract predefinet attrs from shapes."
  [shape]
  (let [attrs (select-keys shape +style-attrs+)]
    (-> (transient attrs)
        (transform-stroke-attrs)
        (persistent!))))

(defn- make-debug-attrs
  [shape]
  (let [attrs (select-keys shape [:rotation :width :height :x :y])
        xf (map (fn [[x v]]
                    [(keyword (str "data-" (name x))) v]))]
      (into {} xf attrs)))
