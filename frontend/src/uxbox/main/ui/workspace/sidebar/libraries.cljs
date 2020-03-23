;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.libraries
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.shapes.icon :as icon]
   [uxbox.main.ui.workspace.sortable :refer [use-sortable]]
   [uxbox.util.dom :as dom]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.ui.components.tab-container :refer [tab-container tab-element]]))

(mf/defc library-tab []
  [:div.library-tab.icons-tab
   [:select.library-tab-libraries
    [:option.library-tab-libraries-item "Material design"]
    [:option.library-tab-libraries-item "Icon library 1"]
    [:option.library-tab-libraries-item "Icon library 2"]]
   [:div.library-tab-content
    (for [_ (range 0 200)]
      [:div.library-tab-element
       i/trash
       [:span.library-tab-element-name "my-icon.svg"]])]])

(mf/defc images-tab []
  [:div.library-tab.images-tab
   [:select.library-tab-libraries
    [:option.library-tab-libraries-item "Material design"]
    [:option.library-tab-libraries-item "Icon library 1"]
    [:option.library-tab-libraries-item "Icon library 2"]]
   [:div.library-tab-content
    (for [_ (range 0 200)]
      [:div.library-tab-element
       [:img {:src "https://www.placecage.com/c/200/300"}]
       [:span.library-tab-element-name "my-icon.svg"]])]])

(mf/defc libraries-toolbox
  []
  (let [locale (i18n/use-locale)]
    [:div#libraries.tool-window
     [:div.tool-window-bar
      [:div "Libraries"]
      [:div "All libraries"]]
     [:div.tool-window-content
      [:& tab-container {}
       [:& tab-element {:id :icons :title "Icons"} [:& library-tab]]
       [:& tab-element {:id :images :title "Images"} [:& images-tab]]]]]))
