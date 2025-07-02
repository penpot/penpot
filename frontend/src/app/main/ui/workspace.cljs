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
   [app.main.data.helpers :as dsh]
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
   [app.main.ui.workspace.tokens.export]
   [app.main.ui.workspace.tokens.export.modal]
   [app.main.ui.workspace.tokens.import]
   [app.main.ui.workspace.tokens.import.modal]
   [app.main.ui.workspace.tokens.management.create.modals]
   [app.main.ui.workspace.tokens.settings]
   [app.main.ui.workspace.tokens.themes.create-modal]
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
        page-id     (get page :id)

        {:keys [vport] :as wlocal} (mf/deref refs/workspace-local)
        {:keys [options-mode]} wglobal


        ;; FIXME: pass this down to viewport and reuse it from here
        ;; instead of making an other deref on viewport for the same
        ;; data
        drawing
        (mf/deref refs/workspace-drawing)

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
     (when (not ^boolean hide-ui?)
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
                            :drawing-tool (get drawing :tool)
                            :layout layout
                            :file file
                            :page-id page-id}]])]))

(mf/defc workspace-loader*
  {::mf/private true}
  []
  [:> loader*  {:title (tr "labels.loading")
                :class (stl/css :workspace-loader)
                :overlay true
                :file-loading true}])

(defn- make-team-ref
  [team-id]
  (l/derived (fn [state]
               (let [teams (get state :teams)]
                 (get teams team-id)))
             st/state))

(defn- make-file-ref
  [file-id]
  (l/derived (fn [state]
               ;; NOTE: for ensure ordering of execution, we need to
               ;; wait the file initialization completly success until
               ;; mark this file availablea and unlock the rendering
               ;; of the following components
               (when (= (get state :current-file-id) file-id)
                 (let [files (get state :files)
                       file  (get files file-id)]
                   (-> file
                       (dissoc :data)
                       (assoc ::has-data (contains? file :data))))))
             st/state
             =))

(defn- make-page-ref
  [file-id page-id]
  (l/derived (fn [state]
               (let [current-page-id (get state :current-page-id)]
                 ;; NOTE: for ensure ordering of execution, we need to
                 ;; wait the page initialization completly success until
                 ;; mark this file availablea and unlock the rendering
                 ;; of the following components
                 (when (= current-page-id page-id)
                   (dsh/lookup-page state file-id page-id))))
             st/state))

(mf/defc workspace-page*
  {::mf/private true}
  [{:keys [page-id file-id file layout wglobal]}]
  (let [page-ref (mf/with-memo [file-id page-id]
                   (make-page-ref file-id page-id))
        page     (mf/deref page-ref)]

    (mf/with-effect []
      (let [focus-out #(st/emit! (dw/workspace-focus-lost))
            key       (events/listen globals/window "blur" focus-out)]
        (partial events/unlistenByKey key)))

    (mf/with-effect [file-id page-id]
      (st/emit! (dw/initialize-page file-id page-id))
      (fn []
        (st/emit! (dw/finalize-page file-id page-id))))

    (if (some? page)
      [:> workspace-content* {:file file
                              :page page
                              :wglobal wglobal
                              :layout layout}]
      [:> workspace-loader*])))

(mf/defc workspace*
  {::mf/wrap [mf/memo]}
  [{:keys [team-id project-id file-id page-id layout-name]}]

  (let [file-id          (hooks/use-equal-memo file-id)
        page-id          (hooks/use-equal-memo page-id)

        layout           (mf/deref refs/workspace-layout)
        wglobal          (mf/deref refs/workspace-global)

        team-ref         (mf/with-memo [team-id]
                           (make-team-ref team-id))
        file-ref         (mf/with-memo [file-id]
                           (make-file-ref file-id))

        team             (mf/deref team-ref)
        file             (mf/deref file-ref)

        file-loaded?     (get file ::has-data)

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

    (mf/with-effect [team-id file-id]
      (st/emit! (dw/initialize-workspace team-id file-id))
      (fn []
        (st/emit! ::dps/force-persist
                  (dw/finalize-workspace team-id file-id))))

    (mf/with-effect [file-id page-id file-loaded?]
      (when (and file-loaded? (not page-id))
        (st/emit! (dcm/go-to-workspace :file-id file-id ::rt/replace true))))

    [:> (mf/provider ctx/current-project-id) {:value project-id}
     [:> (mf/provider ctx/current-file-id) {:value file-id}
      [:> (mf/provider ctx/current-page-id) {:value page-id}
       [:> (mf/provider ctx/design-tokens) {:value design-tokens?}
        [:> (mf/provider ctx/workspace-read-only?) {:value read-only?}
         [:> modal-container*]
         [:section {:class (stl/css :workspace)
                    :style {:background-color background-color
                            :touch-action "none"}}
          [:> context-menu*]
          (if (and file-loaded? page-id)
            [:> workspace-page*
             {:page-id page-id
              :file-id file-id
              :file file
              :wglobal wglobal
              :layout layout}]
            [:> workspace-loader*])]]]]]]))
