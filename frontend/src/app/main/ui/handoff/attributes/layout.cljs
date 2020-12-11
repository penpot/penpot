;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.handoff.attributes.layout
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.i18n :refer [t]]
   [app.common.math :as mth]
   [app.main.ui.icons :as i]
   [app.util.code-gen :as cg]
   [app.main.ui.components.copy-button :refer [copy-button]]))

(def properties [:width :height :x :y :radius :rx])
(def params
  {:to-prop {:x "left"
             :y "top"
             :rotation "transform"
             :rx "border-radius"}
   :format  {:rotation #(str/fmt "rotate(%sdeg)" %)}})

(defn copy-data
  ([shape]
   (apply copy-data shape properties))
  ([shape & properties]
   (cg/generate-css-props shape properties params)))

(mf/defc layout-block
  [{:keys [shape locale]}]
  [:*
   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.width")]
    [:div.attributes-value (mth/precision (:width shape) 2) "px"]
    [:& copy-button {:data (copy-data shape :width)}]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.height")]
    [:div.attributes-value (mth/precision (:height shape) 2) "px"]
    [:& copy-button {:data (copy-data shape :height)}]]

   (when (not= (:x shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.left")]
      [:div.attributes-value (mth/precision (:x shape) 2) "px"]
      [:& copy-button {:data (copy-data shape :x)}]])

   (when (not= (:y shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.top")]
      [:div.attributes-value (mth/precision (:y shape) 2) "px"]
      [:& copy-button {:data (copy-data shape :y)}]])

   (when (and (:rx shape) (not= (:rx shape) 0))
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.radius")]
      [:div.attributes-value (mth/precision (:rx shape) 2) "px"]
      [:& copy-button {:data (copy-data shape :rx)}]])

   (when (not= (:rotation shape 0) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.rotation")]
      [:div.attributes-value (mth/precision (:rotation shape) 2) "deg"]
      [:& copy-button {:data (copy-data shape :rotation)}]])])


(mf/defc layout-panel
  [{:keys [shapes locale]}]
  [:div.attributes-block
   [:div.attributes-block-title
    [:div.attributes-block-title-text (t locale "handoff.attributes.layout")]
    (when (= (count shapes) 1)
      [:& copy-button {:data (copy-data (first shapes))}])]

   (for [shape shapes]
     [:& layout-block {:shape shape
                       :locale locale}])])
