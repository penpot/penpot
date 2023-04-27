;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace
  (:import goog.events.EventType)
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.persistence :as dwp]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-observer]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.colorpalette :refer [colorpalette]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu.context-menu :refer [context-menu]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.header :refer [header]]
   [app.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.sidebar.collapsable-button.collapsable-button :refer [collapsed-button]]
   [app.main.ui.workspace.sidebar.component.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.textpalette :refer [textpalette]]
   [app.main.ui.workspace.viewport :refer [viewport]]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.router :as rt]
   [debug :refer [debug?]]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Workspace

(mf/defc workspace-content
  {::mf/wrap-props false}
  [props]
  (let [selected (mf/deref refs/selected-shapes)
        file     (obj/get props "file")
        layout   (obj/get props "layout")
        page-id  (obj/get props "page-id")

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

     [:section.workspace-content {:key (dm/str "workspace-" page-id)
                                  :ref node-ref}
      [:section.workspace-viewport
       (when (debug? :coordinates)
         [:& coordinates/coordinates {:colorpalette? colorpalette?}])

       (when (debug? :history-overlay)
         [:div.history-debug-overlay
          [:button {:on-click #(st/emit! dw/reinitialize-undo)} "CLEAR"]
          [:& history-toolbox]])
       [:& viewport {:file file
                     :wlocal wlocal
                     :wglobal wglobal
                     :selected selected
                     :layout layout}]]]

     (when-not hide-ui?
       [:*
        [:& left-toolbar {:layout layout}]
        (if (:collapse-left-sidebar layout)
          [:& collapsed-button]
          [:& left-sidebar {:layout layout}])
        [:& right-sidebar {:section options-mode
                           :selected selected
                           :layout layout}]])]))

(def trimmed-page-ref (l/derived :workspace-trimmed-page st/state =))

(mf/defc workspace-page
  [{:keys [file layout page-id wglobal] :as props}]

  (let [prev-page-id (hooks/use-previous page-id)]
    (mf/with-effect
      [page-id]
      (when (and prev-page-id (not= prev-page-id page-id))
        (st/emit! (dw/finalize-page prev-page-id)))

      (if (nil? page-id)
        (st/emit! (dw/go-to-page))
        (st/emit! (dw/initialize-page page-id))))

    (when (mf/deref trimmed-page-ref)
      [:& workspace-content {:page-id page-id
                             :file file
                             :wglobal wglobal
                             :layout layout}])))

(mf/defc workspace-loader
  []
  [:div.workspace-loader
   i/loader-pencil])

(mf/defc workspace
  {::mf/wrap [mf/memo]}
  [{:keys [project-id file-id page-id layout-name] :as props}]
  (let [file                 (mf/deref refs/workspace-file)
        project              (mf/deref refs/workspace-project)
        layout               (mf/deref refs/workspace-layout)
        wglobal              (mf/deref refs/workspace-global)
        ready?               (mf/deref refs/workspace-ready?)
        workspace-read-only? (mf/deref refs/workspace-read-only?)

        components-v2        (features/use-feature :components-v2)
        new-css-system       (features/use-feature :new-css-system)

        background-color     (:background-color wglobal)

        focus-out
        (mf/use-callback
         (fn []
           (st/emit! (dw/workspace-focus-lost))))]

    (mf/use-effect
     (mf/deps focus-out)
     (fn []
       (let [keys [(events/listen globals/document EventType.FOCUSOUT focus-out)]]
         #(doseq [key keys]
            (events/unlistenByKey key)))))

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize-layout layout-name)))

    (mf/with-effect [project-id file-id]
      (st/emit! (dw/initialize-file project-id file-id))
      (fn []
        (st/emit! ::dwp/force-persist
                  (dw/finalize-file project-id file-id))))

    ;; Set html theme color and close any non-modal dialog that may be still open
    (mf/with-effect
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
         [:& (mf/provider ctx/new-css-system) {:value new-css-system}
          [:& (mf/provider ctx/workspace-read-only?) {:value workspace-read-only?}
          [:section#workspace {:style {:background-color background-color
                                       :touch-action "none"}}
           (when (not (:hide-ui layout))
             [:& header {:file file
                         :page-id page-id
                         :project project
                         :layout layout}])

           [:& context-menu]

          (if ready?
            [:& workspace-page {:page-id page-id
                                :file file
                                :wglobal wglobal
                                :layout layout}]
             [:& workspace-loader])]]]]]]]]))

(mf/defc remove-graphics-dialog
  {::mf/register modal/components
   ::mf/register-as :remove-graphics-dialog}
  [{:keys [] :as ctx}]
  (let [remove-state (mf/deref refs/remove-graphics)
        project      (mf/deref refs/workspace-project)
        close        #(modal/hide!)
        reload-file  #(dom/reload-current-window)
        nav-out      #(st/emit! (rt/navigate :dashboard-files
                                             {:team-id (:team-id project)
                                                               :project-id (:id project)}))]
    (mf/use-effect
      (fn []
        #(st/emit! (dw/clear-remove-graphics))))
    [:div.modal-overlay
     [:div.modal-container.remove-graphics-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "workspace.remove-graphics.title" (:file-name ctx))]]
       (if (and (:completed remove-state) (:error remove-state))
         [:div.modal-close-button
          {:on-click close} i/close]
         [:div.modal-close-button
          {:on-click nav-out}
          i/close])]
      (if-not (and (:completed remove-state) (:error remove-state))
        [:div.modal-content
         [:p (tr "workspace.remove-graphics.text1")]
         [:p (tr "workspace.remove-graphics.text2")]
         [:p.progress-message (tr "workspace.remove-graphics.progress"
                                  (:current remove-state)
                                  (:total remove-state))]]
        [:*
         [:div.modal-content
          [:p.error-message [:span i/close] (tr "workspace.remove-graphics.error-msg")]
          [:p (tr "workspace.remove-graphics.error-hint")]]
         [:div.modal-footer
          [:div.action-buttons
           [:input.button-secondary {:type "button"
                                     :value (tr "labels.close")
                                     :on-click close}]
           [:input.button-primary {:type "button"
                                   :value (tr "labels.reload-file")
                                   :on-click reload-file}]]]])]]))
