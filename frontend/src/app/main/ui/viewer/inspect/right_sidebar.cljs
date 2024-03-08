;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.inspect.right-sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.component :as ctk]
   [app.main.refs :as refs]
   [app.main.ui.components.shape-icon :as sir]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
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
    :or {from :viewer}}]
  (let [section        (mf/use-state :info #_:code)
        objects        (or objects (:objects page))
        shapes         (or shapes
                           (resolve-shapes objects selected))
        first-shape    (first shapes)
        page-id        (or page-id (:id page))
        file-id        (or file-id (:id file))

        libraries      (get-libraries from)

        file           (mf/deref refs/viewer-file)
        components-v2  (dm/get-in file [:data :options :components-v2])
        main-instance? (if components-v2
                         (ctk/main-instance? first-shape)
                         true)

        handle-change-tab
        (mf/use-fn
         (mf/deps from on-change-section)
         (fn [new-section]
           (reset! section new-section)
           (when on-change-section
             (on-change-section new-section))))

        handle-expand
        (mf/use-fn
         (mf/deps on-expand)
         (fn []
           (when on-expand (on-expand))))

        navigate-to-help
        (mf/use-fn
         (fn []
           (dom/open-new-window "https://help.penpot.app/user-guide/inspect/")))]

    (mf/use-effect
     (mf/deps shapes handle-change-tab)
     (fn []
       (when-not (seq shapes)
         (handle-change-tab :info))))

    [:aside {:class (stl/css-case :settings-bar-right true
                                  :viewer-code (= from :viewer))}
     (if (seq shapes)
       [:div {:class (stl/css :tool-windows)}
        [:div {:class (stl/css :shape-row)}
         (if (> (count shapes) 1)
           [:*
            [:span {:class (stl/css :layers-icon)} i/layers]
            [:span {:class (stl/css :layer-title)} (tr "inspect.tabs.code.selected.multiple" (count shapes))]]
           [:*
            [:span {:class (stl/css :shape-icon)}
             [:& sir/element-icon {:shape first-shape :main-instance? main-instance?}]]
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
            [:span {:class (stl/css :layer-title)} (:name first-shape)]])]
        [:div {:class (stl/css :inspect-content)}
         [:& tab-container {:on-change-tab handle-change-tab
                            :selected @section
                            :content-class (stl/css :tab-content)
                            :header-class (stl/css :tab-header)}
          [:& tab-element {:id :info :title (tr "inspect.tabs.info")}
           [:& attributes {:page-id page-id
                           :objects objects
                           :file-id file-id
                           :frame frame
                           :shapes shapes
                           :from from
                           :libraries libraries
                           :share-id share-id}]]

          [:& tab-element {:id :code :title (tr "inspect.tabs.code")}
           [:& code {:frame frame
                     :shapes shapes
                     :on-expand handle-expand
                     :from from}]]]]]
       [:div {:class (stl/css :empty)}
        [:div {:class (stl/css :code-info)}
         [:span {:class (stl/css :placeholder-icon)}
          i/code]
         [:span {:class (stl/css :placeholder-label)}
          (tr "inspect.empty.select")]]
        [:div {:class (stl/css :help-info)}
         [:span {:class (stl/css :placeholder-icon)}
          i/help]
         [:span {:class (stl/css :placeholder-label)}
          (tr "inspect.empty.help")]]
        [:button {:class (stl/css :more-info-btn)
                  :on-click navigate-to-help}
         (tr "inspect.empty.more-info")]])]))
