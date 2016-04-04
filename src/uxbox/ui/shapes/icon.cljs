(ns uxbox.ui.shapes.icon
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.shapes :as ush]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.util.dom :as dom]))

;; --- Icon Component

(defn on-mouse-down
  [event {:keys [id group] :as shape} selected drawing?]
  (let [selected? (contains? selected id)]
    (when-not (:blocked shape)
      (cond
        drawing?
        nil

        (and group (:locked (ush/resolve-parent shape)))
        nil

        (and (not selected?) (empty? selected))
        (do
          (dom/stop-propagation event)
          (uuc/acquire-action! "ui.shape.move")
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
          (uuc/acquire-action! "ui.shape.move"))))))

(defn on-mouse-up
  [event {:keys [id group] :as shape}]
  (cond
    (and group (:locked (ush/resolve-parent shape)))
    nil

    :else
    (do
      (dom/stop-propagation event)
      (uuc/release-action! "ui.shape"))))

(declare handlers)

(defmethod uusc/render-component :default ;; :builtin/icon
  [own shape]
  (let [{:keys [id x y width height group]} shape
        selected (rum/react uusc/selected-shapes-l)
        drawing? (rum/react uusc/drawing-state-l)
        selected? (contains? selected id)
        on-mouse-down #(on-mouse-down % shape selected drawing?)
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
    (let [{:keys [x y width height]} (ush/outer-rect' shape)]
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

(defmethod uusc/render-shape :builtin/icon
  [{:keys [data id] :as shape} _]
  (let [key (str id)
        rfm (ush/transformation shape)
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
