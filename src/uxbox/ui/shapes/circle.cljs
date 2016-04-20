(ns uxbox.ui.shapes.circle
  (:require [sablono.core :refer-macros [html]]
            [cuerdas.core :as str]
            [rum.core :as rum]
            [lentes.core :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.workspace :as dw]
            [uxbox.ui.core :as uuc]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.keyboard :as kbd]
            [uxbox.ui.shapes.core :as uusc]
            [uxbox.ui.shapes.icon :as uusi]
            [uxbox.util.geom :as geom]
            [uxbox.util.dom :as dom]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circle Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare handlers)

;; (defmethod uusc/render-component :default ;; :icon
;;   [own shape]
;;   (let [{:keys [id x y width height group]} shape
;;         selected (rum/react uusc/selected-shapes-l)
;;         selected? (contains? selected id)
;;         on-mouse-down #(uusi/on-mouse-down % shape selected)
;;         on-mouse-up #(uusi/on-mouse-up % shape)]
;;     (html
;;      [:g.shape {:class (when selected? "selected")
;;                 :on-mouse-down on-mouse-down
;;                 :on-mouse-up on-mouse-up}
;;       (uusc/render-shape shape #(uusc/shape %))
;;       (when (and selected? (= (count selected) 1))
;;         (handlers shape))])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Circle Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (defn- handlers-render
;;   [own shape]
;;   (letfn [(on-mouse-down [vid event]
;;             (dom/stop-propagation event)
;;             (uuc/acquire-action! "ui.shape.resize"
;;                                  {:vid vid :shape (:id shape)}))

;;           (on-mouse-up [vid event]
;;             (dom/stop-propagation event)
;;             (uuc/release-action! "ui.shape.resize"))]
;;     (let [{:keys [x y width height]} (geom/outer-rect shape)]
;;       (html
;;        [:g.controls
;;         [:rect {:x x :y y :width width :height height :stroke-dasharray "5,5"
;;                 :style {:stroke "#333" :fill "transparent"
;;                         :stroke-opacity "1"}}]
;;         [:circle.top-left
;;          (merge uusc/+circle-props+
;;                 {:on-mouse-up #(on-mouse-up 1 %)
;;                  :on-mouse-down #(on-mouse-down 1 %)
;;                  :cx x
;;                  :cy y})]
;;         [:circle.top-right
;;          (merge uusc/+circle-props+
;;                 {:on-mouse-up #(on-mouse-up 2 %)
;;                  :on-mouse-down #(on-mouse-down 2 %)
;;                  :cx (+ x width)
;;                  :cy y})]
;;         [:circle.bottom-left
;;          (merge uusc/+circle-props+
;;                 {:on-mouse-up #(on-mouse-up 3 %)
;;                  :on-mouse-down #(on-mouse-down 3 %)
;;                  :cx x
;;                  :cy (+ y height)})]
;;         [:circle.bottom-right
;;          (merge uusc/+circle-props+
;;                 {:on-mouse-up #(on-mouse-up 4 %)
;;                  :on-mouse-down #(on-mouse-down 4 %)
;;                  :cx (+ x width)
;;                  :cy (+ y height)})]]))))

;; (def ^:const handlers
;;   (mx/component
;;    {:render handlers-render
;;     :name "handlers"
;;     :mixins [mx/static]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod uusc/render-shape :circle
  [{:keys [id] :as shape}]
  (let [key (str id)
        rfm (geom/transformation-matrix shape)
        props (select-keys shape [:cx :cy :rx :ry])
        attrs (-> (uusc/extract-style-attrs shape)
                  (merge {:id key :key key :transform (str rfm)})
                  (merge props))]
    (html
     [:ellipse attrs])))
