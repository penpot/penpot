;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.right-sidebar
  (:require
   [app.main.refs :as refs]
   [app.main.ui.components.shape-icon :as si]
   [app.main.ui.components.tabs-container :refer [tabs-container tabs-element]]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.inspect.attributes :refer [attributes]]
   [app.main.ui.viewer.inspect.code :refer [code]]
   [app.main.ui.viewer.inspect.selection-feedback :refer [resolve-shapes]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- get-libraries
  "Retrieve all libraries, including the local file, on workspace or viewer"
  [from]
  (if (= from :workspace)
    (let [workspace-data (deref refs/workspace-data)
          {:keys [id] :as local} workspace-data
          libraries (deref refs/workspace-libraries)]
      (-> libraries
          (assoc id {:id id
                     :data local})))
    (let [viewer-data     (deref refs/viewer-data)
          local           (get-in viewer-data [:file :data])
          id              (deref refs/current-file-id)
          libraries (:libraries viewer-data)]
      (-> libraries
          (assoc id {:id id
                     :data local})))))

(mf/defc right-sidebar
  [{:keys [frame page objects file selected shapes page-id file-id share-id from on-change-section on-expand]
    :or {from :inspect}}]
  (let [section       (mf/use-state :info #_:code)
        objects       (or objects (:objects page))
        shapes        (or shapes
                          (resolve-shapes objects selected))
        first-shape   (first shapes)
        page-id       (or page-id (:id page))
        file-id       (or file-id (:id file))

        libraries      (get-libraries from)

        handle-change-tab
        (mf/use-callback
         (mf/deps from on-change-section)
         (fn [new-section]
           (reset! section new-section)
           (when on-change-section
             (on-change-section new-section))))

        handle-expand
        (mf/use-callback
         (mf/deps on-expand)
         (fn []
           (when on-expand (on-expand))))]

    (mf/use-effect
     (mf/deps shapes handle-change-tab)
     (fn []
       (when-not (seq shapes)
         (handle-change-tab :info))))

    [:aside.settings-bar.settings-bar-right
     [:div.settings-bar-inside
      (if (seq shapes)
        [:div.tool-window
         [:div.tool-window-bar.big
          (if (> (count shapes) 1)
            [:*
             [:span.tool-window-bar-icon i/layers]
             [:span.tool-window-bar-title (tr "inspect.tabs.code.selected.multiple" (count shapes))]]
            [:*
             [:span.tool-window-bar-icon
              [:& si/element-icon {:shape first-shape}]]
             ;; Execution time translation strings:
             ;;   inspect.tabs.code.selected.circle
             ;;   inspect.tabs.code.selected.component
             ;;   inspect.tabs.code.selected.curve
             ;;   inspect.tabs.code.selected.frame
             ;;   inspect.tabs.code.selected.group
             ;;   inspect.tabs.code.selected.image
             ;;   inspect.tabs.code.selected.mask
             ;;   inspect.tabs.code.selected.path
             ;;   inspect.tabs.code.selected.rect
             ;;   inspect.tabs.code.selected.svg-raw
             ;;   inspect.tabs.code.selected.text
             [:span.tool-window-bar-title (:name first-shape)]])]
         [:div.tool-window-content.inspect
          [:& tabs-container {:on-change-tab handle-change-tab
                              :selected @section}
           [:& tabs-element {:id :info :title (tr "inspect.tabs.info")}
            [:& attributes {:page-id page-id
                            :objects objects
                            :file-id file-id
                            :frame frame
                            :shapes shapes
                            :from from
                            :libraries libraries
                            :share-id share-id}]]

           [:& tabs-element {:id :code :title (tr "inspect.tabs.code")}
            [:& code {:frame frame
                      :shapes shapes
                      :on-expand handle-expand
                      :from from}]]]]]
        [:div.empty
         [:span.tool-window-bar-icon i/code]
         [:div (tr "inspect.empty.select")]
         [:span.tool-window-bar-icon i/help]
         [:div (tr "inspect.empty.help")]
         [:button.btn-primary.action {:on-click #(dom/open-new-window "https://help.penpot.app/user-guide/inspect/")} (tr "inspect.empty.more-info")]])]]))
