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
   [app.main.ui.icons :as i]
   [app.main.ui.components.color-bullet :refer [color-bullet color-name]]))


(mf/defc layout-panel [{:keys [shapes]}]
  (prn "???" shapes)
  [:*
   [:div.attributes-block
    [:div.attributes-block-title
     [:div.attributes-block-title-text "Layout"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-unit-row
     [:div.attributes-label "Width"]
     [:div.attributes-value "100px"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-unit-row
     [:div.attributes-label "Height"]
     [:div.attributes-value "100px"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-unit-row
     [:div.attributes-label "Top"]
     [:div.attributes-value "100px"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-unit-row
     [:div.attributes-label "Left"]
     [:div.attributes-value "100px"]
     [:button.attributes-copy-button i/copy]]]

   [:div.attributes-block
    [:div.attributes-block-title
     [:div.attributes-block-title-text "Fill"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-shadow-row
     [:div.attributes-label "Drop"]
     [:div.attributes-shadow
      [:div.attributes-label "X"]
      [:div.attributes-value "4"]]

     [:div.attributes-shadow
      [:div.attributes-label "Y"]
      [:div.attributes-value "4"]]

     [:div.attributes-shadow
      [:div.attributes-label "B"]
      [:div.attributes-value "0"]]

     [:div.attributes-shadow
      [:div.attributes-label "B"]
      [:div.attributes-value "0"]]

     [:button.attributes-copy-button i/copy]]

    [:div.attributes-color-row
     [:& color-bullet {:color {:color "#000000" :opacity 0.5}}]

     [:*
      [:div "#000000"]
      [:div "100%"]]

     [:select
      [:option "Hex"]
      [:option "RGBA"]
      [:option "HSLA"]]

     [:button.attributes-copy-button i/copy]]

    [:div.attributes-stroke-row
     [:div.attributes-label "Width"]
     [:div.attributes-value "1px"]
     [:div.attributes-value "Solid"]
     [:div.attributes-label "Center"]
     [:button.attributes-copy-button i/copy]]]

   [:div.attributes-block
    [:div.attributes-block-title
     [:div.attributes-block-title-text "Content"]
     [:button.attributes-copy-button i/copy]]

    [:div.attributes-content-row
     [:div.attributes-content
      "Hi, how are you"]
     [:button.attributes-copy-button i/copy]]]

   [:div.attributes-block
    [:div.attributes-image-row
     [:div.attributes-image
      #_[:img {:src "https://www.publico.es/tremending/wp-content/uploads/2019/05/Cxagv.jpg"}]
      #_[:img {:src "https://i.blogs.es/3861b2/grumpy-cat/1366_2000.png"}]
      [:img {:src "https://abs.twimg.com/favicons/twitter.ico"}]
      ]]
    [:button.download-button "Dowload source image"]]

   ])
