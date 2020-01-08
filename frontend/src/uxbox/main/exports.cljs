;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.exports
  "The main logic for SVG export functionality."
  (:require
   [cljsjs.react.dom.server]
   [rumext.alpha :as mf]
   [uxbox.main.geom :as geom]
   [uxbox.main.ui.shapes.canvas :as canvas]
   [uxbox.main.ui.shapes.circle :as circle]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.shapes.image :as image]
   [uxbox.main.ui.shapes.path :as path]
   [uxbox.main.ui.shapes.rect :as rect]
   [uxbox.main.ui.shapes.text :as text]))

(defn- render-html
  [component]
  (.renderToStaticMarkup js/ReactDOMServer component))

(mf/defc background
  []
  [:rect
   {:x 0 :y 0
    :width "100%"
    :height "100%"
    :fill "#b1b2b5"}])

(defn- calculate-dimensions
  [data]
  (let [shapes (vals (:shapes-by-id data))
        shape (geom/shapes->rect-shape shapes)]
    {:width (+ (:x shape) (:width shape) 100)
     :height (+ (:y shape) (:height shape) 100)}))

(mf/defc shape-wrapper
  [{:keys [shape] :as props}]
  (when (and shape (not (:hidden shape)))
    (case (:type shape)
      :canvas [:& rect/rect-shape {:shape shape}]
      :curve [:& path/path-shape {:shape shape}]
      :text [:& text/text-shape {:shape shape}]
      :icon [:& icon/icon-shape {:shape shape}]
      :rect [:& rect/rect-shape {:shape shape}]
      :path [:& path/path-shape {:shape shape}]
      :image [:& image/image-shape {:shape shape}]
      :circle [:& circle/circle-shape {:shape shape}])))

(mf/defc page-svg
  [{:keys [data width height] :as props}]
  (let [shapes-by-id (:shapes-by-id data)
        shapes (map #(get shapes-by-id %) (:shapes data []))
        canvas (map #(get shapes-by-id %) (:canvas data []))
        dim (calculate-dimensions data)]
    [:svg {
           ;; :width width
           ;; :height height
           :view-box (str "0 0 " (:width dim) " " (:height dim))
           :version "1.1"
           :xmlnsXlink "http://www.w3.org/1999/xlink"
           :xmlns "http://www.w3.org/2000/svg"}
     (background)
     [:*
      (for [item canvas]
        [:& shape-wrapper {:shape item :key (:id item)}])
      (for [item shapes]
        [:& shape-wrapper {:shape item :key (:id item)}])]]))

;; (defn render
;;   [{:keys [data] :as page}]
;;   (try
;;     (-> (mf/element page-svg #js {:data data})
;;         (render-html))
;;     (catch :default e
;;       (js/console.log e)
;;       nil)))
