;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace
  (:require
   [beicon.core :as rx]
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.streams :as ms]
   [uxbox.main.ui.confirm]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.hooks :as hooks]
   [uxbox.main.ui.workspace.viewport :refer [viewport coordinates]]
   [uxbox.main.ui.workspace.colorpalette :refer [colorpalette]]
   [uxbox.main.ui.workspace.context-menu :refer [context-menu]]
   [uxbox.main.ui.workspace.header :refer [header]]
   [uxbox.main.ui.workspace.rules :refer [horizontal-rule vertical-rule]]
   [uxbox.main.ui.workspace.scroll :as scroll]
   [uxbox.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [uxbox.main.ui.workspace.sidebar.history :refer [history-dialog]]
   [uxbox.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.common.geom.point :as gpt]))

;; --- Workspace

(mf/defc workspace-content
  [{:keys [page file layout project] :as params}]
  (let [left-sidebar? (not (empty? (keep layout [:layers :sitemap
                                                 :document-history :libraries])))
        right-sidebar? (not (empty? (keep layout [:icons :element-options])))
        classes (classnames
                 :no-tool-bar-right (not right-sidebar?)
                 :no-tool-bar-left (not left-sidebar?))

        local (mf/deref refs/workspace-local)]

    [:*
     (when (:colorpalette layout)
       [:& colorpalette {:left-sidebar? left-sidebar?
                         :project project}])

     [:section.workspace-content {:class classes}
      [:& history-dialog]

      [:section.workspace-viewport
       (when (contains? layout :rules)
         [:*
          [:div.empty-rule-square]
          [:& horizontal-rule {:zoom (:zoom local)
                               :vbox (:vbox local)
                               :vport (:vport local)}]
          [:& vertical-rule {:zoom (:zoom local 1)
                             :vbox (:vbox local)
                             :vport (:vport local)}]
          [:& coordinates]])


       [:& viewport {:page page
                     :key (:id page)
                     :file file
                     :local local
                     :layout layout}]]]

     [:& left-toolbar {:page page :layout layout}]

     ;; Aside
     (when left-sidebar?
       [:& left-sidebar {:file file :page page :layout layout}])
     (when right-sidebar?
       [:& right-sidebar {:page page :layout layout}])]))

(mf/defc workspace-page
  [{:keys [project file layout page-id] :as props}]

  (mf/use-effect
   (mf/deps page-id)
   (fn []
     (st/emit! (dw/initialize-page page-id))
     #(st/emit! (dw/finalize-page page-id))))
  (when-let [page (mf/deref refs/workspace-page)]
    [:& workspace-content {:page page
                           :project project
                           :file file
                           :layout layout}]))

(mf/defc workspace-loader
  []
  [:div.workspace-loader
   i/loader-pencil])

(mf/defc workspace
  [{:keys [project-id file-id page-id] :as props}]
  (mf/use-effect #(st/emit! dw/initialize-layout))
  (mf/use-effect
   (mf/deps project-id file-id)
   (fn []
     (st/emit! (dw/initialize project-id file-id))
     #(st/emit! (dw/finalize project-id file-id))))

  (hooks/use-shortcuts dw/shortcuts)

  (let [file (mf/deref refs/workspace-file)
        project (mf/deref refs/workspace-project)
        layout (mf/deref refs/workspace-layout)]
    [:section
     [:& header {:file file
                 :project project
                 :layout layout}]

     [:& context-menu]

     (if (and (and file project)
              (:initialized file))
       [:& workspace-page {:file file
                           :project project
                           :layout layout
                           :page-id page-id}]
       [:& workspace-loader])]))
