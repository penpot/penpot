;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.persistence :as dps]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-observer]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu :refer [context-menu]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.palette :refer [palette]]
   [app.main.ui.workspace.plugins]
   [app.main.ui.workspace.sidebar :refer [left-sidebar right-sidebar]]
   [app.main.ui.workspace.sidebar.collapsable-button :refer [collapsed-button]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.viewport :refer [viewport]]
   [app.renderer-v2 :as renderer]
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
  (let [palete-size (mf/use-state nil)
        selected (mf/deref refs/selected-shapes)

        {:keys [vport] :as wlocal} (mf/deref refs/workspace-local)
        {:keys [options-mode]} wglobal

        colorpalette?  (:colorpalette layout)
        textpalette?   (:textpalette layout)
        hide-ui?       (:hide-ui layout)

        on-resize
        (mf/use-fn
         (mf/deps vport)
         (fn [resize-type size]
           (when (and vport (not= size vport))
             (st/emit! (dw/update-viewport-size resize-type size)))))

        on-resize-palette
        (mf/use-fn
         (fn [size]
           (reset! palete-size size)))

        node-ref (use-resize-observer on-resize)]
    [:*
     (when (not hide-ui?)
       [:& palette {:layout layout
                    :on-change-palette-size on-resize-palette}])

     [:section
      {:key (dm/str "workspace-" page-id)
       :class (stl/css :workspace-content)
       :ref node-ref}

      [:section {:class (stl/css :workspace-viewport)}
       (when (dbg/enabled? :coordinates)
         [:& coordinates/coordinates {:colorpalette? colorpalette?}])

       (when (dbg/enabled? :history-overlay)
         [:div {:class (stl/css :history-debug-overlay)}
          [:button {:on-click #(st/emit! dw/reinitialize-undo)} "CLEAR"]
          [:& history-toolbox]])

       [:& viewport {:file file
                     :wlocal wlocal
                     :wglobal wglobal
                     :selected selected
                     :layout layout
                     :palete-size
                     (when (and (or colorpalette? textpalette?) (not hide-ui?))
                       @palete-size)}]]]

     (when-not hide-ui?
       [:*
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
  [:> loader*  {:title (tr "labels.loading")
                :class (stl/css :workspace-loader)
                :overlay true}])

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

        team             (mf/deref refs/team)
        file             (mf/deref refs/workspace-file)
        project          (mf/deref refs/workspace-project)

        team-id          (:team-id project)
        file-name        (:name file)
        permissions      (:permissions team)

        read-only?       (mf/deref refs/workspace-read-only?)
        read-only?       (or read-only? (not (:can-edit permissions)))

        file-ready*      (mf/with-memo [file-id]
                           (make-file-ready-ref file-id))
        file-ready?      (mf/deref file-ready*)

        components-v2?   (features/use-feature "components/v2")

        background-color (:background-color wglobal)]

    (mf/with-effect []
      (st/emit! (dps/initialize-persistence)))

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize-layout layout-name)))

    (mf/with-effect [file-name]
      (when file-name
        (dom/set-html-title (tr "title.workspace" file-name))))

    (mf/with-effect [project-id file-id]
      (st/emit! (dw/initialize-file project-id file-id))
      (fn []
        (st/emit! ::dps/force-persist
                  (dc/stop-picker)
                  (modal/hide)
                  (ntf/hide)
                  (dw/finalize-file project-id file-id))))

    (mf/with-effect [file-ready?]
      (when (and file-ready? (contains? cf/flags :renderer-v2))
        (renderer/print-msg "hello from wasm fn!")))

    [:& (mf/provider ctx/current-file-id) {:value file-id}
     [:& (mf/provider ctx/current-project-id) {:value project-id}
      [:& (mf/provider ctx/current-team-id) {:value team-id}
       [:& (mf/provider ctx/current-page-id) {:value page-id}
        [:& (mf/provider ctx/components-v2) {:value components-v2?}
         [:& (mf/provider ctx/workspace-read-only?) {:value read-only?}
          [:& (mf/provider ctx/team-permissions) {:value permissions}
           [:section {:class (stl/css :workspace)
                      :style {:background-color background-color
                              :touch-action "none"}}
            [:& context-menu]
            (if ^boolean file-ready?
              [:& workspace-page {:page-id page-id
                                  :file file
                                  :wglobal wglobal
                                  :layout layout}]
              [:& workspace-loader])]]]]]]]]))
