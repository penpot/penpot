;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.colorpalette :refer [colorpalette]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.header :refer [header]]
   [app.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.rules :refer [horizontal-rule vertical-rule]]
   [app.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.viewport :refer [viewport]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

;; --- Workspace

(mf/defc workspace-rules
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [zoom  (or (obj/get props "zoom") 1)
        vbox  (obj/get props "vbox")
        vport (obj/get props "vport")
        colorpalette? (obj/get props "colorpalette?")]

    [:*
     [:div.empty-rule-square]
     [:& horizontal-rule {:zoom zoom
                          :vbox vbox
                          :vport vport}]
     [:& vertical-rule {:zoom zoom
                        :vbox vbox
                        :vport vport}]
     [:& coordinates/coordinates {:colorpalette? colorpalette?}]]))

(mf/defc workspace-content
  {::mf/wrap-props false}
  [props]
  (let [selected (mf/deref refs/selected-shapes)
        local    (mf/deref refs/viewport-data)

        {:keys [zoom vbox vport options-mode]} local
        file   (obj/get props "file")
        layout (obj/get props "layout")]
    [:*
     (when (:colorpalette layout)
       [:& colorpalette])

     [:section.workspace-content
      [:section.workspace-viewport
       (when (contains? layout :rules)
         [:& workspace-rules {:zoom zoom
                              :vbox vbox
                              :vport vport
                              :colorpalette? (contains? layout :colorpalette)}])

       [:& viewport {:file file
                     :local local
                     :selected selected
                     :layout layout}]]]

     [:& left-toolbar {:layout layout}]

     ;; Aside
     [:& left-sidebar {:layout layout}]
     [:& right-sidebar {:section options-mode
                        :selected selected}]]))

(def trimmed-page-ref (l/derived :trimmed-page st/state =))

(mf/defc workspace-page
  [{:keys [file layout page-id] :as props}]
  (mf/use-layout-effect
   (mf/deps page-id)
   (fn []
     (if (nil? page-id)
       (st/emit! (dw/go-to-page))
       (st/emit! (dw/initialize-page page-id)))

     (fn []
       (when page-id
         (st/emit! (dw/finalize-page page-id))))))

  (when (mf/deref trimmed-page-ref)
    [:& workspace-content {:key page-id
                           :file file
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
        layout  (mf/deref refs/workspace-layout)]

    ;; Setting the layout preset by its name
    (mf/use-effect
     (mf/deps layout-name)
     (fn []
       (st/emit! (dw/setup-layout layout-name))))

    (mf/use-effect
     (mf/deps project-id file-id)
     (fn []
       (st/emit! (dw/initialize-file project-id file-id))
       (fn []
         (st/emit! ::dwp/force-persist
                   (dw/finalize-file project-id file-id)))))

    ;; Close any non-modal dialog that may be still open
    (mf/use-effect
     (fn [] (st/emit! dm/hide)))

    ;; Set properly the page title
    (mf/use-effect
     (mf/deps (:name file))
     (fn []
       (when (:name file)
         (dom/set-html-title (tr "title.workspace" (:name file))))))

    [:& (mf/provider ctx/current-file-id) {:value (:id file)}
     [:& (mf/provider ctx/current-team-id) {:value (:team-id project)}
      [:& (mf/provider ctx/current-project-id) {:value (:id project)}
       [:& (mf/provider ctx/current-page-id) {:value page-id}
        [:section#workspace
         [:& header {:file file
                     :page-id page-id
                     :project project
                     :layout layout}]

         [:& context-menu]

         (if (and (and file project)
                  (:initialized file))
           [:& workspace-page {:key (str "page-" page-id)
                               :page-id page-id
                               :file file
                               :layout layout}]
           [:& workspace-loader])]]]]]))

