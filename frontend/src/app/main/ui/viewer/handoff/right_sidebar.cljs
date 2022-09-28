;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.handoff.right-sidebar
  (:require
   [app.common.data :as d]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.handoff.attributes :refer [attributes]]
   [app.main.ui.viewer.handoff.code :refer [code]]
   [app.main.ui.viewer.handoff.selection-feedback :refer [resolve-shapes]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc right-sidebar
  [{:keys [frame page file selected]}]
  (let [expanded      (mf/use-state false)
        section       (mf/use-state :info #_:code)
        shapes        (resolve-shapes (:objects page) selected)

        first-shape   (first shapes)

        selected-type (or (:type first-shape) :not-found)
        selected-type (if (= selected-type :group)
                        (if (some? (:component-id first-shape))
                          :component
                          (if (:masked-group? first-shape)
                            :mask
                            :group))
                        selected-type)]

    [:aside.settings-bar.settings-bar-right {:class (when @expanded "expanded")}
     [:div.settings-bar-inside
      (when (seq shapes)
        [:div.tool-window
         [:div.tool-window-bar.big
          (if (> (count shapes) 1)
            [:*
             [:span.tool-window-bar-icon i/layers]
             [:span.tool-window-bar-title (tr "handoff.tabs.code.selected.multiple" (count shapes))]]
            [:*
             [:span.tool-window-bar-icon
              [:& si/element-icon {:shape first-shape}]]
             [:span.tool-window-bar-title (->> selected-type d/name (str "handoff.tabs.code.selected.") (tr))]])]
         [:div.tool-window-content
          [:& tab-container {:on-change-tab #(do
                                               (reset! expanded false)
                                               (reset! section %))
                             :selected @section}
           [:& tab-element {:id :info :title (tr "handoff.tabs.info")}
            [:& attributes {:page-id (:id page)
                            :file-id (:id file)
                            :frame frame
                            :shapes shapes}]]

           [:& tab-element {:id :code :title (tr "handoff.tabs.code")}
            [:& code {:frame frame
                      :shapes shapes
                      :on-expand #(swap! expanded not)}]]]]])]]))
