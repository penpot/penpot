;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.layout
  (:require
   [app.common.spec.radius :as ctr]
   [app.main.ui.components.copy-button :refer [copy-button]]
   [app.util.code-gen :as cg]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def properties [:width :height :x :y :radius :rx :r1])

(def params
  {:to-prop {:x "left"
             :y "top"
             :rotation "transform"
             :rx "border-radius"
             :r1 "border-radius"}
   :format  {:rotation #(str/fmt "rotate(%sdeg)" %)
             :r1 #(apply str/fmt "%spx, %spx, %spx, %spx" %)}
   :multi   {:r1 [:r1 :r2 :r3 :r4]}})

(defn copy-data
  ([shape]
   (apply copy-data shape properties))
  ([shape & properties]
   (cg/generate-css-props shape properties params)))

(mf/defc layout-block
  [{:keys [shape]}]
  (let [selrect (:selrect shape)
        {:keys [width height x y]} selrect]
    [:*
     [:div.attributes-unit-row
      [:div.attributes-label (tr "handoff.attributes.layout.width")]
      [:div.attributes-value width "px"]
      [:& copy-button {:data (copy-data selrect :width)}]]

     [:div.attributes-unit-row
      [:div.attributes-label (tr "handoff.attributes.layout.height")]
      [:div.attributes-value height "px"]
      [:& copy-button {:data (copy-data selrect :height)}]]

     (when (not= (:x shape) 0)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "handoff.attributes.layout.left")]
        [:div.attributes-value x "px"]
        [:& copy-button {:data (copy-data selrect :x)}]])

     (when (not= (:y shape) 0)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "handoff.attributes.layout.top")]
        [:div.attributes-value y "px"]
        [:& copy-button {:data (copy-data selrect :y)}]])

     (when (ctr/radius-1? shape)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "handoff.attributes.layout.radius")]
        [:div.attributes-value (:rx shape 0) "px"]
        [:& copy-button {:data (copy-data shape :rx)}]])

     (when (ctr/radius-4? shape)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "handoff.attributes.layout.radius")]
        [:div.attributes-value
         (:r1 shape) ", "
         (:r2 shape) ", "
         (:r3 shape) ", "
         (:r4 shape) "px"]
        [:& copy-button {:data (copy-data shape :r1)}]])

     (when (not= (:rotation shape 0) 0)
       [:div.attributes-unit-row
        [:div.attributes-label (tr "handoff.attributes.layout.rotation")]
        [:div.attributes-value (:rotation shape) "deg"]
        [:& copy-button {:data (copy-data shape :rotation)}]])]))


(mf/defc layout-panel
  [{:keys [shapes]}]
  [:div.attributes-block
   [:div.attributes-block-title
    [:div.attributes-block-title-text (tr "handoff.attributes.layout")]
    (when (= (count shapes) 1)
      [:& copy-button {:data (copy-data (first shapes))}])]

   (for [shape shapes]
     [:& layout-block {:shape shape}])])
