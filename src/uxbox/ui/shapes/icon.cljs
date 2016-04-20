(ns uxbox.ui.shapes.icon
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.data.shapes :as uds]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]))

;; --- Icon Component

(defn on-mouse-down
  [event {:keys [id group] :as shape} selected]
  (let [selected? (contains? selected id)
        drawing? @uusc/drawing-state-l]
    (when-not (:blocked shape)
      (cond
        (or drawing?
            (and group (:locked (geom/resolve-parent shape))))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (rs/emit! (uds/select-shape id))
          (uuc/acquire-action! "ui.shape.move"))

        (and (not selected?) (not (empty? selected)))
        (do
          (dom/stop-propagation event)
          (if (kbd/shift? event)
            (rs/emit! (uds/select-shape id))
            (do
              (rs/emit! (uds/deselect-all)
                        (uds/select-shape id))
              (uuc/acquire-action! "ui.shape.move"))))

        :else
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! "ui.shape.move"))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (geom/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (uuc/release-action! "ui.shape"))))

(declare handlers)

(defmethod uusc/render-component :default ;; :icon
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

;; --- Icon Handlers

(defn- handlers-render
  [own shape]
  (letfn [(on-mouse-down [vid event]
            (dom/stop-propagation event)
            (uuc/acquire-action! "ui.shape.resize"
                                 {:vid vid :shape (:id shape)}))

          (on-mouse-up [vid event]
            (dom/stop-propagation event)
            (uuc/release-action! "ui.shape.resize"))]
    (let [{:keys [x y width height]} (geom/inner-rect shape)]
      (html
       [:g.controls
        [:rect {:x x :y y :width width :height height :stroke-dasharray "5,5"
                :style {:stroke "#333" :fill "transparent"
                        :stroke-opacity "1"}}]
        [:circle.top-left
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 1 %)
                 :on-mouse-down #(on-mouse-down 1 %)
                 :cx x
                 :cy y})]
        [:circle.top-right
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 2 %)
                 :on-mouse-down #(on-mouse-down 2 %)
                 :cx (+ x width)
                 :cy y})]
        [:circle.bottom-left
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 3 %)
                 :on-mouse-down #(on-mouse-down 3 %)
                 :cx x
                 :cy (+ y height)})]
        [:circle.bottom-right
         (merge uusc/+circle-props+
                {:on-mouse-up #(on-mouse-up 4 %)
                 :on-mouse-down #(on-mouse-down 4 %)
                 :cx (+ x width)
                 :cy (+ y height)})]]))))

(def ^:const handlers
  (mx/component
   {:render handlers-render
    :name "handlers"
    :mixins [mx/static]}))

;; --- Shape & Shape Svg

(defmethod uusc/render-shape :icon
  [{:keys [data id] :as shape} _]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        attrs (merge {:id key :key key :transform (str rfm)}
                     (uusc/extract-style-attrs shape)
                     (uusc/make-debug-attrs shape))]
    (html
     [:g attrs data])))

(defmethod uusc/render-shape-svg :icon
  [{:keys [data id view-box] :as shape}]
  (let [key (str "icon-svg-" id)
        view-box (apply str (interpose " " view-box))
        props {:view-box view-box :id key :key key}]
    (html
     [:svg props data])))
