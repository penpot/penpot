(ns uxbox.ui.shapes.icon
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as sh]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.dom :as dom]))

(def ^:private ^:const selection-circle-style
  {:fillOpacity "0.5"
   :strokeWidth "1px"
   :vectorEffect "non-scaling-stroke"})

(def ^:private ^:const default-selection-props
  {:r 5 :style selection-circle-style
   :fill "#333"
   :stroke "#333"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icon Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-mouse-down
  [event {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)]
    (when-not (:blocked shape)
      (cond
        (and group (:locked (sh/resolve-parent shape)))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! :shape/movement)
          (rs/emit! (dw/select-shape id)))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (rs/emit! (dw/select-shape id))
            (rs/emit! (dw/deselect-all)
                      (dw/select-shape id))))

        :else
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! :shape/movement))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (sh/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (uuc/release-action! :shape/movement))))

(declare handlers)

(defmethod uusc/render-component :default ;; :builtin/icon
  [own shape]
  (let [{:keys [id x y width height group]} shape
        selected (rum/react uusc/selected-shapes-l)
        selected? (contains? selected id)
        on-mouse-down #(on-mouse-down % shape selected)
        on-mouse-up #(on-mouse-up % shape)]
    (html
     [:g.shape {:class (when selected? "selected")
                :on-mouse-down on-mouse-down
                :on-mouse-up on-mouse-up}
      (uusc/render-shape shape #(uusc/shape %))
      (when (and selected? (= (count selected) 1))
        (handlers shape))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icon Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- handlers-render
  [own shape]
  (let [{:keys [x y width height]} (sh/outer-rect' shape)]
    (html
     [:g.controls
      [:rect {:x x :y y :width width :height height :stroke-dasharray "5,5"
              :style {:stroke "#333" :fill "transparent"
                      :stroke-opacity "1"}}]
      [:circle.top-left (merge default-selection-props
                                    {:cx x :cy y})]
      [:circle.top-right (merge default-selection-props
                                {:cx (+ x width) :cy y})]
      [:circle.bottom-left (merge default-selection-props
                                  {:cx x :cy (+ y height)})]
      [:circle.bottom-right (merge default-selection-props
                                   {:cx (+ x width) :cy (+ y height)})]])))

(def ^:const handlers
  (mx/component
   {:render handlers-render
    :name "handlers"
    :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape & Shape Svg
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod uusc/render-shape :builtin/icon
  [{:keys [data id] :as shape} _]
  (let [key (str id)
        rfm (sh/-transformation shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (uusc/extract-style-attrs shape)
                     (uusc/make-debug-attrs shape))]
    (html
     [:g attrs data])))

(defmethod uusc/render-shape-svg :builtin/icon
  [{:keys [data id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}]
    (html
     [:svg props data])))
