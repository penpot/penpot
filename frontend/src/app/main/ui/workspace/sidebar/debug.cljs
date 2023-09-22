;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.debug
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc debug-panel
  []
  (let [on-toggle-enabled
        (mf/use-fn
         (fn [event option]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (dbg/toggle! option)
           (js* "app.main.reinit()")))

        on-close
        (mf/use-fn
         (fn []
           (st/emit! (dw/remove-layout-flag :debug-panel))))]
    [:div.debug-panel
     [:div.debug-panel-header
      [:div.debug-panel-close-button
       {:on-click on-close} i/close]
      [:div.debug-panel-title "Debugging tools"]]

     [:div.debug-panel-inner
      (for [option (sort-by d/name dbg/options)]
        [:div.debug-option {:key (d/name option)
                            :on-click #(on-toggle-enabled % option)}
         [:input {:type "checkbox"
                  :id (d/name option)
                  :on-change #(on-toggle-enabled % option)
                  :checked (dbg/enabled? option)}]
         [:div.field.check
          (if (dbg/enabled? option)
            [:span.checked i/checkbox-checked]
            [:span.unchecked i/checkbox-unchecked])]
         [:label {:for (d/name option)} (d/name option)]])]]))
