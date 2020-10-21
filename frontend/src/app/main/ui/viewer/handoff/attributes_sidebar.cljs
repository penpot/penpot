;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.handoff.attributes-sidebar
  (:require
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]))

(mf/defc info-panel []
  [:div.element-options])

(mf/defc code-panel []
  [:div.element-options])

(mf/defc attributes-sidebar []
  (let [section (mf/use-state :info #_:code)]
    [:aside.settings-bar.settings-bar-right
     [:div.settings-bar-inside
      [:div.tool-window
       [:div.tool-window-bar.big
        [:span.tool-window-bar-icon i/text]
        [:span.tool-window-bar-title "Text"]]
       [:div.tool-window-content
        [:& tab-container {:on-change-tab #(reset! section %)
                           :selected @section}
         [:& tab-element {:id :info :title "Info"}
          [:& info-panel]]

         [:& tab-element {:id :code :title "Code"}
          [:& code-panel]]]]]]]))

