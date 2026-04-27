;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.mcp-activity
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.mcp :as mcp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (deprecated-icon/icon-xref :close (stl/css :close-icon)))

(defn- phase-class [phase]
  (case phase
    :received (stl/css :activity-phase :phase-received)
    :completed (stl/css :activity-phase :phase-completed)
    :failed (stl/css :activity-phase :phase-failed)
    (stl/css :activity-phase)))

(defn- phase-label [phase]
  (case phase
    :received (tr "workspace.mcp.activity.phase.received")
    :completed (tr "workspace.mcp.activity.phase.completed")
    :failed (tr "workspace.mcp.activity.phase.failed")
    (dm/str phase)))

(mf/defc mcp-activity-dialog
  {::mf/register modal/components
   ::mf/register-as :mcp-activity}
  []
  (let [mcp-state (mf/deref refs/mcp)
        entries   (-> mcp-state :activity vec)

        on-clear
        (mf/use-fn
         (fn []
           (st/emit! (mcp/clear-mcp-activity))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :mcp-activity)}
      [:button {:class (stl/css :close-btn)
                :type "button"
                :on-click modal/hide!}
       close-icon]

      [:div {:class (stl/css :modal-title)}
       (tr "workspace.mcp.activity.title")]

      [:div {:class (stl/css :activity-toolbar)}
       [:> button* {:type "button"
                    :variant "secondary"
                    :on-click on-clear
                    :disabled (empty? entries)}
        (tr "workspace.mcp.activity.clear")]]

      [:div {:class (stl/css :activity-scroll)}
       (if (empty? entries)
         [:div {:class (stl/css :activity-empty)}
          (tr "workspace.mcp.activity.empty")]

         (into [:ul {:class (stl/css :activity-list)}]
               (map-indexed
                (fn [idx row]
                  [:li {:key idx
                        :class (stl/css :activity-row)}
                   [:div {:class (stl/css :activity-meta)}
                    [:span {:class (phase-class (:phase row))}
                     (phase-label (:phase row))]
                    [:span (ct/format-inst (:ts row) :localized-date-time)]
                    [:span {:class (stl/css :activity-task)} (:task row)]]
                   (when (:error row)
                     [:div {:class (stl/css :activity-error)} (:error row)])
                   (when (:code-preview row)
                     [:pre {:class (stl/css :activity-code)}
                      (:code-preview row)])])
                entries))])]]))
