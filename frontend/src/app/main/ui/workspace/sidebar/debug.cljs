;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.debug
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.icons :as i]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc debug-panel
  [{:keys [class] :as props}]
  (let [on-toggle-enabled
        (mf/use-fn
         (fn [event option]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (dbg/toggle! option)
           (js* "app.main.reinit(true)")))

        handle-close
        (mf/use-fn
         (fn []
           (st/emit! (dw/remove-layout-flag :debug-panel))))]

    [:div {:class (dm/str class " " (stl/css :debug-panel))}
     [:div {:class (stl/css :panel-title)}
      [:span "Debugging tools"]
      [:> icon-button* {:variant "ghost"
                        :aria-label (tr "labels.close")
                        :on-click handle-close
                        :icon "close"}]]

     [:div {:class (stl/css :debug-panel-inner)}
      (for [option (sort-by d/name dbg/options)]
        [:div {:key (d/name option) :class (stl/css :checkbox-wrapper)}
         [:span {:class (stl/css-case :checkbox-icon true :global/checked (dbg/enabled? option))
                 :on-click #(on-toggle-enabled % option)}
          (when (dbg/enabled? option) i/status-tick)]

         [:input {:type "checkbox"
                  :id (d/name option)
                  :key (d/name option)
                  :on-change #(on-toggle-enabled % option)
                  :checked (dbg/enabled? option)}]
         [:label {:for (d/name option)} (d/name option)]])]]))
