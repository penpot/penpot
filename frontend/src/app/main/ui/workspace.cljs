;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.data.persistence :as dps]
   [app.main.data.plugins :as dpl]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.router :as-alias rt]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-observer]]
   [app.main.ui.modal :refer [modal-container*]]
   [app.main.ui.workspace.colorpicker]
   [app.main.ui.workspace.context-menu :refer [context-menu*]]
   [app.main.ui.workspace.coordinates :as coordinates]
   [app.main.ui.workspace.libraries]
   [app.main.ui.workspace.nudge]
   [app.main.ui.workspace.palette :refer [palette]]
   [app.main.ui.workspace.plugins]
   [app.main.ui.workspace.sidebar :refer [left-sidebar* right-sidebar*]]
   [app.main.ui.workspace.sidebar.collapsable-button :refer [collapsed-button]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox*]]
   [app.main.ui.workspace.tokens.modals]
   [app.main.ui.workspace.tokens.modals.themes]
   [app.main.ui.workspace.viewport :refer [viewport*]]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :as i18n :refer [tr]]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(mf/defc workspace-content*
  {::mf/private true}
  [{:keys [file layout page wglobal]}]
  (let [palete-size (mf/use-state nil)
        selected    (mf/deref refs/selected-shapes)
        page-id     (:id page)

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
          [:> history-toolbox*]])

       [:> viewport*
        {:file file
         :page page
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
          [:> left-sidebar* {:layout layout
                             :file file
                             :page-id page-id}])
        [:> right-sidebar* {:section options-mode
                            :selected selected
                            :layout layout
                            :file file
                            :page-id page-id}]])]))

(mf/defc workspace-loader*
  {::mf/private true}
  []
  [:> loader*  {:title (tr "labels.loading")
                :class (stl/css :workspace-loader)
                :overlay true}])

(mf/defc workspace-page*
  {::mf/private true}
  [{:keys [page-id file-id file layout wglobal]}]
  (let [page (mf/deref refs/workspace-page)]

    (mf/with-effect []
      (let [focus-out #(st/emit! (dw/workspace-focus-lost))
            key       (events/listen globals/window "blur" focus-out)]
        (partial events/unlistenByKey key)))

    (mf/with-effect [file-id page-id]
      (st/emit! (dw/initialize-page file-id page-id))
      (fn []
        (when page-id
          (st/emit! (dw/finalize-page file-id page-id)))))

    (if (some? page)
      [:> workspace-content* {:file file
                              :page page
                              :wglobal wglobal
                              :layout layout}]
      [:> workspace-loader*])))

(def ^:private ref:file-without-data
  (l/derived (fn [file]
               (-> file
                   (dissoc :data)
                   (assoc ::has-data (contains? file :data))))
             refs/file
             =))

(mf/defc workspace*
  {::mf/props :obj
   ::mf/wrap [mf/memo]}
  [{:keys [project-id file-id page-id layout-name]}]

  (let [file-id          (hooks/use-equal-memo file-id)
        page-id          (hooks/use-equal-memo page-id)

        layout           (mf/deref refs/workspace-layout)
        wglobal          (mf/deref refs/workspace-global)

        team             (mf/deref refs/team)
        file             (mf/deref ref:file-without-data)

        file-name        (:name file)
        permissions      (:permissions team)

        read-only?       (mf/deref refs/workspace-read-only?)
        read-only?       (or read-only? (not (:can-edit permissions)))

        design-tokens?   (features/use-feature "design-tokens/v1")

        background-color (:background-color wglobal)]

    (mf/with-effect []
      (st/emit! (dps/initialize-persistence)
                (dpl/update-plugins-permissions-peek)))

    ;; Setting the layout preset by its name
    (mf/with-effect [layout-name]
      (st/emit! (dw/initialize-workspace-layout layout-name)))

    (mf/with-effect [file-name]
      (when file-name
        (dom/set-html-title (tr "title.workspace" file-name))))

    (mf/with-effect [file-id]
      (st/emit! (dw/initialize-workspace file-id))
      (fn []
        (st/emit! ::dps/force-persist
                  (dw/finalize-workspace file-id))))

    (mf/with-effect [file page-id]
      (when-not page-id
        (st/emit! (dcm/go-to-workspace :file-id file-id ::rt/replace true))))

    [:> (mf/provider ctx/current-project-id) {:value project-id}
     [:> (mf/provider ctx/current-file-id) {:value file-id}
      [:> (mf/provider ctx/current-page-id) {:value page-id}
       [:> (mf/provider ctx/components-v2) {:value true}
        [:> (mf/provider ctx/design-tokens) {:value design-tokens?}
         [:> (mf/provider ctx/workspace-read-only?) {:value read-only?}
          [:> modal-container*]
          [:section {:class (stl/css :workspace)
                     :style {:background-color background-color
                             :touch-action "none"}}
           [:> context-menu*]

           (if (::has-data file)
             [:> workspace-page*
              {:page-id page-id
               :file-id file-id
               :file file
               :wglobal wglobal
               :layout layout}]
             [:> workspace-loader*])]]]]]]]))
