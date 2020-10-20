;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace
  (:require
   [app.common.geom.point :as gpt]
   [app.main.constants :as c]
   [app.main.data.history :as udh]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.colorpalette :refer [colorpalette]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.comments :refer [comments-layer]]
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.main.ui.workspace.header :refer [header]]
   [app.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.rules :refer [horizontal-rule vertical-rule]]
   [app.main.ui.workspace.scroll :as scroll]
   [app.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.viewport :refer [viewport coordinates]]
   [app.util.dom :as dom]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Workspace

(mf/defc workspace-content
  [{:keys [page-id file layout project] :as params}]
  (let [local          (mf/deref refs/workspace-local)
        left-sidebar?  (:left-sidebar? local)
        right-sidebar? (:right-sidebar? local)
        classes        (dom/classnames
                        :no-tool-bar-right (not right-sidebar?)
                        :no-tool-bar-left (not left-sidebar?))]
    [:*
     (when (:colorpalette layout)
       [:& colorpalette {:left-sidebar? left-sidebar?
                         :team-id (:team-id project)}])

     [:section.workspace-content {:class classes}
      [:section.workspace-viewport
       (when (contains? layout :comments)
         [:& comments-layer {:vbox (:vbox local)
                             :vport (:vport local)
                             :zoom (:zoom local)
                             :page-id page-id
                             :file-id (:id file)}
          ])

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


       [:& viewport {:page-id page-id
                     :key (str page-id)
                     :file file
                     :local local
                     :layout layout}]]]

     [:& left-toolbar {:layout layout}]

     ;; Aside
     (when left-sidebar?
       [:& left-sidebar
        {:file file
         :page-id page-id
         :project project
         :layout layout}])
     (when right-sidebar?
       [:& right-sidebar
        {:page-id page-id
         :file-id (:id file)
         :local local
         :layout layout}])]))

(defn trimmed-page-ref
  [id]
  (l/derived (fn [state]
               (let [page-id (:current-page-id state)
                     data    (:workspace-data state)]
                 (select-keys (get-in data [:pages-index page-id]) [:id :name])))
             st/state =))

(mf/defc workspace-page
  [{:keys [project file layout page-id] :as props}]
  (mf/use-effect
   (mf/deps page-id)
   (fn []
     (st/emit! (dw/initialize-page page-id))
     #(st/emit! (dw/finalize-page page-id))))

  (let [page-ref (mf/use-memo (mf/deps page-id) #(trimmed-page-ref page-id))
        page     (mf/deref page-ref)]
    (when page
      [:& workspace-content {:page page
                             :page-id (:id page)
                             :project project
                             :file file
                             :layout layout}])))

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
     (st/emit! (dw/initialize-file project-id file-id))
     #(st/emit! (dw/finalize-file project-id file-id))))

  (hooks/use-shortcuts dw/shortcuts)

  (let [file    (mf/deref refs/workspace-file)
        project (mf/deref refs/workspace-project)
        layout  (mf/deref refs/workspace-layout)]

    [:section#workspace
     [:& header {:file file
                 :page-id page-id
                 :project project
                 :layout layout}]

     [:& context-menu]

     (if (and (and file project)
              (:initialized file))

       [:& workspace-page {:page-id page-id
                           :project project
                           :file file
                           :layout layout}]
       [:& workspace-loader])]))
