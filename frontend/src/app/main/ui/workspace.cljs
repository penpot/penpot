;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace
  (:require
   [app.common.colors :as clr]
   [app.common.data.macros :as dm]
   [app.main.data.messages :as msg]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks.resize :refer [use-resize-observer]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.colorpalette :refer [colorpalette]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.header :refer [header]]
   [app.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.textpalette :refer [textpalette]]
   [app.main.ui.workspace.viewport :refer [viewport]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [debug :refer [debug?]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Workspace

(mf/defc workspace-content
  {::mf/wrap-props false}
  [props]
  (let [selected (mf/deref refs/selected-shapes)
        file     (obj/get props "file")
        layout   (obj/get props "layout")

        {:keys [vport] :as wlocal} (mf/deref refs/workspace-local)
        {:keys [options-mode] :as wglobal} (obj/get props "wglobal")

        colorpalette? (:colorpalette layout)
        textpalette?  (:textpalette layout)
        hide-ui?      (:hide-ui layout)

        on-resize
        (mf/use-callback
         (mf/deps vport)
         (fn [resize-type size]
           (when (and vport (not= size vport))
             (st/emit! (dw/update-viewport-size resize-type size)))))

        node-ref (use-resize-observer on-resize)]
    [:*
     (when (and colorpalette? (not hide-ui?))
       [:& colorpalette])

     (when (and textpalette? (not hide-ui?))
       [:& textpalette])

     [:section.workspace-content {:ref node-ref}
      [:section.workspace-viewport
       (when (debug? :coordinates)
         [:& coordinates/coordinates {:colorpalette? colorpalette?}])

       [:& viewport {:file file
                     :wlocal wlocal
                     :wglobal wglobal
                     :selected selected
                     :layout layout}]]]

     (when-not hide-ui?
       [:*
        [:& left-toolbar {:layout layout}]
        (if (:collapse-left-sidebar layout)
          [:button.collapse-sidebar.collapsed {:on-click #(st/emit! (dw/toggle-layout-flag :collapse-left-sidebar))}
           i/arrow-slide]
          [:& left-sidebar {:layout layout}])
        [:& right-sidebar {:section options-mode
                           :selected selected
                           :layout layout}]])]))

(def trimmed-page-ref (l/derived :trimmed-page st/state =))

(mf/defc workspace-page
  [{:keys [file layout page-id wglobal] :as props}]

 (mf/with-effect [page-id]
   (if (nil? page-id)
     (st/emit! (dw/go-to-page))
     (st/emit! (dw/initialize-page page-id)))
   (fn []
     (when page-id
       (st/emit! (dw/finalize-page page-id)))))

  (when (mf/deref trimmed-page-ref)
    [:& workspace-content {:key (dm/str page-id)
                           :file file
                           :wglobal wglobal
                           :layout layout}]))

(mf/defc workspace-loader
  []
  [:div.workspace-loader
   i/loader-pencil])

(mf/defc workspace
  {::mf/wrap [mf/memo]}
  [{:keys [project-id file-id page-id layout-name] :as props}]
  (let [file    (mf/deref refs/workspace-file)
        project (mf/deref refs/workspace-project)
        layout  (mf/deref refs/workspace-layout)
        wglobal (mf/deref refs/workspace-global)

        components-v2 (features/use-feature :components-v2)

        background-color (:background-color wglobal)]

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize layout-name)))

    (mf/with-effect [project-id file-id]
      (st/emit! (dw/initialize-file project-id file-id))
      (fn []
        (st/emit! ::dwp/force-persist
                  (dw/finalize-file project-id file-id))))

    ;; Set html theme color and close any non-modal dialog that may be still open
    (mf/with-effect
      (dom/set-html-theme-color clr/gray-50 "dark")
      (st/emit! msg/hide))

    ;; Set properly the page title
    (mf/with-effect [(:name file)]
      (when (:name file)
        (dom/set-html-title (tr "title.workspace" (:name file)))))

    [:& (mf/provider ctx/current-file-id) {:value (:id file)}
     [:& (mf/provider ctx/current-team-id) {:value (:team-id project)}
      [:& (mf/provider ctx/current-project-id) {:value (:id project)}
       [:& (mf/provider ctx/current-page-id) {:value page-id}
        [:& (mf/provider ctx/components-v2) {:value components-v2}
         [:section#workspace {:style {:background-color background-color}}
          (when (not (:hide-ui layout))
            [:& header {:file file
                        :page-id page-id
                        :project project
                        :layout layout}])

          [:& context-menu]

          (if (and (and file project)
                   (:initialized file))
            [:& workspace-page {:key (dm/str "page-" page-id)
                                :page-id page-id
                                :file file
                                :wglobal wglobal
                                :layout layout}]
            [:& workspace-loader])]]]]]]))

