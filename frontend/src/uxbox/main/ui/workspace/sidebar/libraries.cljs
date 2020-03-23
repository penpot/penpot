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

(mf/defc libraries-toolbox
  []
  (let [locale (i18n/use-locale)]
    [:div#libraries.tool-window
     [:div.tool-window-bar
      [:div "Libraries"]
      [:div "All libraries"]]
     [:div.tool-window-content
      [:& tab-container {:selected :icons :on-change-tab #(println "Change tab")}
       [:& tab-element {:id :icons :title "Icons"}
        [:p "ICONS TAB"]]
       [:& tab-element {:id :images :title "Images"}
        [:p "IMAGES TAB"]]]]]))
