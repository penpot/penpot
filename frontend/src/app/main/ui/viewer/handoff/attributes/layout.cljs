;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes.layout
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.util.i18n :refer [t]]
   [app.common.math :as mth]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.handoff.attributes.common :refer [copy-cb]]))

(defn copy-layout [shape]
  (copy-cb shape
           [:width :height :x :y :radius :rx]
           :to-prop {:x "left" :y "top" :rotation "transform" :rx "border-radius"}
           :format {:rotation #(str/fmt "rotate(%sdeg)" %)}))

(mf/defc layout-block
  [{:keys [shape locale]}]
  [:*
   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.width")]
    [:div.attributes-value (mth/precision (:width shape) 2) "px"]
    [:button.attributes-copy-button
     {:on-click (copy-cb shape :width)}
     i/copy]]

   [:div.attributes-unit-row
    [:div.attributes-label (t locale "handoff.attributes.layout.height")]
    [:div.attributes-value (mth/precision (:height shape) 2) "px"]
    [:button.attributes-copy-button
     {:on-click (copy-cb shape :height)}
     i/copy]]

   (when (not= (:x shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.left")]
      [:div.attributes-value (mth/precision (:x shape) 2) "px"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape :x :to-prop "left")}
       i/copy]])
   
   (when (not= (:y shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.top")]
      [:div.attributes-value (mth/precision (:y shape) 2) "px"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape :y :to-prop "top")}
       i/copy]])

   (when (not= (:rx shape) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.radius")]
      [:div.attributes-value (mth/precision (:rx shape) 2) "px"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape :rx :to-prop "border-radius")}
       i/copy]])

   (when (not= (:rotation shape 0) 0)
     [:div.attributes-unit-row
      [:div.attributes-label (t locale "handoff.attributes.layout.rotation")]
      [:div.attributes-value (mth/precision (:rotation shape) 2) "deg"]
      [:button.attributes-copy-button
       {:on-click (copy-cb shape
                           :rotation
                           :to-prop "transform"
                           :format #(str/fmt "rotate(%sdeg)" %))}
       i/copy]])])


(mf/defc layout-panel
  [{:keys [shapes locale]}]
  (let [handle-copy (when (= (count shapes) 1)
                      (copy-layout (first shapes)))]
    [:div.attributes-block
     [:div.attributes-block-title
      [:div.attributes-block-title-text (t locale "handoff.attributes.layout")]
      (when handle-copy
        [:button.attributes-copy-button
         {:on-click handle-copy}
         i/copy])]

     (for [shape shapes]
       [:& layout-block {:shape shape
                         :locale locale}])]))
