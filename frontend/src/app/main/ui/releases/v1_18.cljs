;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.releases.v1-18
  (:require
   [app.main.ui.releases.common :as c]
   [rumext.v2 :as mf]))

(defmethod c/render-release-notes "1.18"
  [{:keys [slide klass next finish navigate version]}]
  (mf/html
   (case @slide
     :start
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/onboarding-version.jpg" :border "0" :alt "What's new release 1.18"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "What's new?"]]
         [:span.release "Version " version]
         [:div.modal-content
          [:p "On this 1.18 release we make Flex Layout even more powerful with smart spacing, absolute position and z-index management."]
          [:p "We also continued implementing accessibility improvements to make Penpot more inclusive and published stability and performance enhancements."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]]]
        [:img.deco {:src "images/deco-left.png" :border "0"}]
        [:img.deco.right {:src "images/deco-right.png" :border "0"}]]]]

     0
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.18-spacing.gif" :border "0" :alt "Spacing management"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Spacing management for Flex layout"]]
         [:div.modal-content
          [:p "Managing Flex Layout spacing is much more intuitive now. Visualize paddings, margins and gaps and drag to resize them."]
          [:p "And not only that, when creating Flex layouts, the spacing is predicted, helping you to maintain your design composition."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     1
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.18-absolute.gif" :border "0" :alt "Position absolute feature"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Absolute position elements in Flex layout"]]
         [:div.modal-content
          [:p "Sometimes you need to freely position an element in a specific place regardless of the size of the layout where it belongs."] 
          [:p "Now you can exclude elements from the Flex layout flow using absolute position."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     2
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.18-z-index.gif" :border "0" :alt "Z-index feature"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "More on Flex layout: z-index"]]
         [:div.modal-content
          [:p "With the new z-index option you can decide the order of overlapping elements while maintaining the layers order."]
          [:p "This is another capability that brings Penpot Flex layout even closer to the power of CSS standards."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click next} "Continue"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]]

     3
     [:div.modal-overlay
      [:div.animated {:class @klass}
       [:div.modal-container.onboarding.feature
        [:div.modal-left
         [:img {:src "images/features/1.18-scale.gif" :border "0" :alt "Scale content proportionally"}]]
        [:div.modal-right
         [:div.modal-title
          [:h2 "Scale content proportionally affects strokes, shadows, blurs and corners"]]
         [:div.modal-content
          [:p "Now you can resize your layers and groups preserving their aspect ratio while scaling their properties proportionally, including strokes, shadows, blurs and corners."]
          [:p "Activate the scale tool by pressing K and scale your elements, maintaining their visual aspect."]]
         [:div.modal-navigation
          [:button.btn-secondary {:on-click finish} "Start!"]
          [:& c/navigation-bullets
           {:slide @slide
            :navigate navigate
            :total 4}]]]]]])))
