;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.messages :as msg]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
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
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.header :refer [header]]
   [app.main.ui.workspace.left-toolbar :refer [left-toolbar]]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.palette :refer [palette]]
   [app.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.sidebar.collapsable-button :refer [collapsed-button]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.textpalette :refer [textpalette]]
   [app.main.ui.workspace.viewport :refer [viewport]]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-file-ready-ref
  [file-id]
  (l/derived (fn [state]
               (let [data (:workspace-data state)]
                 (and (:workspace-ready? state)
                      (= file-id (:current-file-id state))
                      (= file-id (:id data)))))
             st/state))

(defn- make-page-ready-ref
  [page-id]
  (l/derived (fn [state]
               (and (some? page-id)
                    (= page-id (:current-page-id state))))
             st/state))

(mf/defc workspace-content
  {::mf/wrap-props false}
  [{:keys [file layout page-id wglobal]}]
  (let [selected (mf/deref refs/selected-shapes)

        {:keys [vport] :as wlocal} (mf/deref refs/workspace-local)
        {:keys [options-mode]} wglobal

        colorpalette?  (:colorpalette layout)
        textpalette?   (:textpalette layout)
        hide-ui?       (:hide-ui layout)
        new-css-system (mf/use-ctx ctx/new-css-system)

        on-resize
        (mf/use-fn
         (mf/deps vport)
         (fn [resize-type size]
           (when (and vport (not= size vport))
             (st/emit! (dw/update-viewport-size resize-type size)))))

        node-ref (use-resize-observer on-resize)]
    [:*
     (if new-css-system
       [:& palette {:layout layout}]
       [:*
        (when (and colorpalette? (not hide-ui?))
          [:& colorpalette])

        (when (and textpalette? (not hide-ui?))
          [:& textpalette])])

     [:section.workspace-content
      {:key (dm/str "workspace-" page-id)
       :ref node-ref}

      [:section.workspace-viewport
       (when (dbg/enabled? :coordinates)
         [:& coordinates/coordinates {:colorpalette? colorpalette?}])

       (when (dbg/enabled? :history-overlay)
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
          [:& left-sidebar {:layout layout
                            :file file
                            :page-id page-id}])
        [:& right-sidebar {:section options-mode
                           :selected selected
                           :layout layout
                           :file file
                           :page-id page-id}]])]))

(mf/defc workspace-loader
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    [:div {:class (if new-css-system (css :workspace-loader)
                      (dom/classnames :workspace-loader true))}
     i/loader-pencil]))

(mf/defc workspace-page
  {::mf/wrap-props false}
  [{:keys [page-id file layout wglobal]}]
  (let [page-id     (hooks/use-equal-memo page-id)
        page-ready* (mf/with-memo [page-id]
                      (make-page-ready-ref page-id))
        page-ready? (mf/deref page-ready*)]

    (mf/with-effect []
      (let [focus-out #(st/emit! (dw/workspace-focus-lost))
            key       (events/listen globals/window "blur" focus-out)]
        (partial events/unlistenByKey key)))

    (mf/with-effect [page-id]
      (if (some? page-id)
        (st/emit! (dw/initialize-page page-id))
        (st/emit! (dw/go-to-page)))
      (fn []
        (when (some? page-id)
          (st/emit! (dw/finalize-page page-id)))))

    (if ^boolean page-ready?
      [:& workspace-content {:page-id page-id
                             :file file
                             :wglobal wglobal
                             :layout layout}]
      [:& workspace-loader])))

(mf/defc workspace
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [project-id file-id page-id layout-name]}]

  (let [layout           (mf/deref refs/workspace-layout)
        wglobal          (mf/deref refs/workspace-global)
        read-only?       (mf/deref refs/workspace-read-only?)

        file             (mf/deref refs/workspace-file)
        project          (mf/deref refs/workspace-project)

        team-id          (:team-id project)
        file-name        (:name file)

        file-ready*      (mf/with-memo [file-id]
                           (make-file-ready-ref file-id))
        file-ready?      (mf/deref file-ready*)

        components-v2?   (features/use-feature "components/v2")
        new-css-system   (features/use-feature "styles/v2")

        background-color (:background-color wglobal)]

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize-layout layout-name)))

    (mf/with-effect [file-name]
      (when file-name
        (dom/set-html-title (tr "title.workspace" file-name))))

    (mf/with-effect [project-id file-id]
      (st/emit! (dw/initialize-file project-id file-id))
      (fn []
        (st/emit! ::dwp/force-persist
                  (dc/stop-picker)
                  (modal/hide)
                  msg/hide
                  (dw/finalize-file project-id file-id))))

    [:& (mf/provider ctx/current-file-id) {:value file-id}
     [:& (mf/provider ctx/current-project-id) {:value project-id}
      [:& (mf/provider ctx/current-team-id) {:value team-id}
       [:& (mf/provider ctx/current-page-id) {:value page-id}
        [:& (mf/provider ctx/components-v2) {:value components-v2?}
         [:& (mf/provider ctx/new-css-system) {:value new-css-system}
          [:& (mf/provider ctx/workspace-read-only?) {:value read-only?}
           (if new-css-system
             [:section#workspace-refactor {:class (css :workspace)
                                           :style {:background-color background-color
                                                   :touch-action "none"}}
              [:& context-menu]

              (if ^boolean file-ready?
                [:& workspace-page {:page-id page-id
                                    :file file
                                    :wglobal wglobal
                                    :layout layout}]
                [:& workspace-loader])]


             [:section#workspace {:style {:background-color background-color
                                          :touch-action "none"}}
              (when (not (:hide-ui layout))
                [:& header {:file file
                            :page-id page-id
                            :project project
                            :layout layout}])

              [:& context-menu]

              (if ^boolean file-ready?
                [:& workspace-page {:page-id page-id
                                    :file file
                                    :wglobal wglobal
                                    :layout layout}]
                [:& workspace-loader])])]]]]]]]))
