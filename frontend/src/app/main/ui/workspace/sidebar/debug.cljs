;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.debug
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ^:private no-reload-options
  "Debug options that don't require a page reload to take effect.
  These options are handled reactively via okulary subscriptions."
  #{:shape-panel
    :show-ids
    :show-touched})

(mf/defc debug-panel*
  [{:keys [class]}]
  (let [;; dbg/state is an okulary atom; deref'ing it makes this component
        ;; re-render whenever any debug option is toggled, so checkboxes
        ;; reflect the current state without a page reload.
        _dbg   (mf/deref dbg/state)

        on-toggle-enabled
        (mf/use-fn
         (fn [event option]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (dbg/toggle! option)
           (when-not (contains? no-reload-options option)
             (js* "app.main.reinit(true)"))))]

    [:div {:class (dm/str class " " (stl/css :debug-panel))}
     [:div {:class (stl/css :debug-panel-inner)}
      (for [option (sort-by d/name dbg/options)]
        [:div {:key (d/name option) :class (stl/css :checkbox-wrapper)}
         [:span {:class (stl/css-case :checkbox-icon true :global/checked (dbg/enabled? option))
                 :on-click #(on-toggle-enabled % option)}
          (when (dbg/enabled? option) deprecated-icon/status-tick)]

         [:input {:type "checkbox"
                  :id (d/name option)
                  :key (d/name option)
                  :on-change #(on-toggle-enabled % option)
                  :checked (dbg/enabled? option)}]
         [:label {:for (d/name option)} (d/name option)]])]]))
