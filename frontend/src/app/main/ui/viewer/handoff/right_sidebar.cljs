;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.handoff.right-sidebar
  (:require
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.handoff.attributes :refer [attributes]]
   [app.main.ui.viewer.handoff.code :refer [code]]
   [app.main.ui.viewer.handoff.selection-feedback :refer [resolve-shapes]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc right-sidebar
  [{:keys [frame page file selected shapes page-id file-id from]
    :or {from :handoff}}]
  (let [expanded      (mf/use-state false)
        section       (mf/use-state :info #_:code)
        shapes        (or shapes
                          (resolve-shapes (:objects page) selected))

        first-shape   (first shapes)
        page-id       (or page-id (:id page))
        file-id       (or file-id (:id file))]

    [:aside.settings-bar.settings-bar-right {:class (when @expanded "expanded")}
     [:div.settings-bar-inside
      (if (seq shapes)
        [:div.tool-window
         [:div.tool-window-bar.big
          (if (> (count shapes) 1)
            [:*
             [:span.tool-window-bar-icon i/layers]
             [:span.tool-window-bar-title (tr "handoff.tabs.code.selected.multiple" (count shapes))]]
            [:*
             [:span.tool-window-bar-icon
              [:& si/element-icon {:shape first-shape}]]
             ;; Execution time translation strings:
             ;;   handoff.tabs.code.selected.circle
             ;;   handoff.tabs.code.selected.component
             ;;   handoff.tabs.code.selected.curve
             ;;   handoff.tabs.code.selected.frame
             ;;   handoff.tabs.code.selected.group
             ;;   handoff.tabs.code.selected.image
             ;;   handoff.tabs.code.selected.mask
             ;;   handoff.tabs.code.selected.path
             ;;   handoff.tabs.code.selected.rect
             ;;   handoff.tabs.code.selected.svg-raw
             ;;   handoff.tabs.code.selected.text
             [:span.tool-window-bar-title (:name first-shape)]])]
         [:div.tool-window-content.inspect
          [:& tab-container {:on-change-tab #(do
                                               (reset! expanded false)
                                               (reset! section %)
                                               (when (= from :workspace)
                                                 (dw/set-inspect-expanded false)))
                             :selected @section}
           [:& tab-element {:id :info :title (tr "handoff.tabs.info")}
            [:& attributes {:page-id page-id
                            :file-id file-id
                            :frame frame
                            :shapes shapes}]]

           [:& tab-element {:id :code :title (tr "handoff.tabs.code")}
            [:& code {:frame frame
                      :shapes shapes
                      :on-expand (fn []
                                   (when (= from :workspace)
                                     (st/emit! (dw/set-inspect-expanded (not @expanded))))
                                   (swap! expanded not))
                      :from from}]]]]]
        [:div.empty
         [:span.tool-window-bar-icon i/code]
         [:div (tr "handoff.empty.select")]
         [:span.tool-window-bar-icon i/help]
         [:div (tr "handoff.empty.help")]
         [:button.btn-primary.action {:on-click #(dom/open-new-window "https://help.penpot.app/user-guide/inspect/")} (tr "handoff.empty.more-info")]])]]))
