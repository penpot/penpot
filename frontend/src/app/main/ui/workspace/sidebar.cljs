;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.workspace.comments :refer [comments-sidebar*]]
   [app.main.ui.workspace.left-header :refer [left-header]]
   [app.main.ui.workspace.right-header :refer [right-header*]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]
   [app.main.ui.workspace.sidebar.debug :refer [debug-panel]]
   [app.main.ui.workspace.sidebar.debug-shape-info :refer [debug-shape-info]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox*]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox*]]
   [app.main.ui.workspace.sidebar.shortcuts :refer [shortcuts-container]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap]]
   [app.main.ui.workspace.sidebar.versions :refer [versions-toolbox*]]
   [app.main.ui.workspace.tokens.sidebar :refer [tokens-sidebar-tab*]]
   [app.util.debug :as dbg]
   [app.util.i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; --- Left Sidebar (Component)

(mf/defc collapse-button
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [on-click] :as props}]
  ;; NOTE: This custom button may be replace by an action button when this variant is designed
  [:button {:class (stl/css :collapse-sidebar-button)
            :on-click on-click}
   [:> icon* {:icon-id "arrow"
              :size "s"
              :aria-label (tr "workspace.sidebar.collapse")}]])

(mf/defc left-sidebar*
  {::mf/wrap [mf/memo]
   ::mf/props :obj}
  [{:keys [layout file page-id] :as props}]
  (let [options-mode   (mf/deref refs/options-mode-global)
        project        (mf/deref refs/project)
        file-id        (get file :id)

        design-tokens? (features/use-feature "design-tokens/v1")
        mode-inspect?  (= options-mode :inspect)
        shortcuts?     (contains? layout :shortcuts)
        show-debug?    (contains? layout :debug-panel)

        section        (cond
                         (or mode-inspect? (contains? layout :layers)) :layers
                         (contains? layout :assets) :assets
                         (contains? layout :tokens) :tokens)

        {on-pointer-down :on-pointer-down
         on-lost-pointer-capture :on-lost-pointer-capture
         on-pointer-move :on-pointer-move
         parent-ref :parent-ref
         size :size}
        (use-resize-hook :left-sidebar 275 275 500 :x false :left)

        {on-pointer-down-pages :on-pointer-down
         on-lost-pointer-capture-pages  :on-lost-pointer-capture
         on-pointer-move-pages :on-pointer-move
         size-pages-opened :size}
        (use-resize-hook :sitemap 200 38 "0.6" :y false nil)

        show-pages?    (mf/use-state true)
        toggle-pages   (mf/use-fn #(reset! show-pages? not))
        size-pages     (mf/with-memo [show-pages? size-pages-opened]
                         (if @show-pages? size-pages-opened 32))

        handle-collapse
        (mf/use-fn #(st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))

        on-tab-change
        (mf/use-fn
         (fn [id]
           (when (= id "tokens")
             (st/emit! (ptk/event ::ev/event {::ev/name "open-tokens-tab"})))
           (st/emit! (dcm/go-to-workspace :layout (keyword id)))))

        layers-tab
        (mf/html
         [:article {:class (stl/css :layers-tab)
                    :style #js {"--height" (str size-pages "px")}}

          [:& sitemap {:layout layout
                       :toggle-pages toggle-pages
                       :show-pages? @show-pages?
                       :size size-pages}]

          (when @show-pages?
            [:div {:class (stl/css :resize-area-horiz)
                   :on-pointer-down on-pointer-down-pages
                   :on-lost-pointer-capture on-lost-pointer-capture-pages
                   :on-pointer-move on-pointer-move-pages}

             [:div {:class (stl/css :resize-handle-horiz)}]])

          [:& layers-toolbox {:size-parent size
                              :size size-pages}]])


        assets-tab
        (mf/html [:& assets-toolbox {:size (- size 58) :file-id file-id}])

        tokens-tab
        (when design-tokens?
          (mf/html [:> tokens-sidebar-tab*]))

        tabs
        (if ^boolean mode-inspect?
          #js [#js {:label (tr "workspace.sidebar.layers")
                    :id "layers"
                    :content layers-tab}]
          (if ^boolean design-tokens?
            #js [#js {:label (tr "workspace.sidebar.layers")
                      :id "layers"
                      :content layers-tab}
                 #js {:label (tr "workspace.toolbar.assets")
                      :id "assets"
                      :content assets-tab}
                 #js {:label "Tokens"
                      :id "tokens"
                      :content tokens-tab}]
            #js [#js {:label (tr "workspace.sidebar.layers")
                      :id "layers"
                      :content layers-tab}
                 #js {:label (tr "workspace.toolbar.assets")
                      :id "assets"
                      :content assets-tab}]))]

    [:& (mf/provider muc/sidebar) {:value :left}
     [:aside {:ref parent-ref
              :id "left-sidebar-aside"
              :data-testid "left-sidebar"
              :data-size (str size)
              :class (stl/css-case :left-settings-bar true
                                   :global/two-row    (<= size 300)
                                   :global/three-row  (and (> size 300) (<= size 400))
                                   :global/four-row  (> size 400))
              :style #js {"--width" (dm/str size "px")}}

      [:& left-header
       {:file file
        :layout layout
        :project project
        :page-id page-id
        :class (stl/css :left-header)}]

      [:div {:on-pointer-down on-pointer-down
             :on-lost-pointer-capture on-lost-pointer-capture
             :on-pointer-move on-pointer-move
             :class (stl/css :resize-area)}]
      (cond
        (true? shortcuts?)
        [:& shortcuts-container {:class (stl/css :settings-bar-content)}]

        (true? show-debug?)
        [:& debug-panel {:class (stl/css :settings-bar-content)}]

        :else
        [:div {:class (stl/css  :settings-bar-content)}
         [:> tab-switcher* {:tabs tabs
                            :default-selected "layers"
                            :selected (name section)
                            :on-change-tab on-tab-change
                            :class (stl/css :left-sidebar-tabs)
                            :action-button-position "start"
                            :action-button (mf/html [:& collapse-button {:on-click handle-collapse}])}]])]]))

;; --- Right Sidebar (Component)

(mf/defc right-sidebar*
  {::mf/wrap [mf/memo]}
  [{:keys [layout section file page-id] :as props}]
  (let [drawing-tool     (:tool (mf/deref refs/workspace-drawing))

        is-comments?     (= drawing-tool :comments)
        is-history?      (contains? layout :document-history)
        is-inspect?      (= section :inspect)

        dbg-shape-panel? (dbg/enabled? :shape-panel)

        current-section* (mf/use-state :info)
        current-section  (deref current-section*)

        can-be-expanded?
        (or dbg-shape-panel?
            (and (not is-comments?)
                 (not is-history?)
                 is-inspect?
                 (= current-section :code)))

        {:keys [on-pointer-down on-lost-pointer-capture on-pointer-move set-size size]}
        (use-resize-hook :code 276 276 768 :x true :right)

        on-change-section
        (mf/use-fn
         (fn [section]
           (reset! current-section* section)))

        on-close-history
        (mf/use-fn #(st/emit! (dw/remove-layout-flag :document-history)))

        on-expand
        (mf/use-fn
         (mf/deps size)
         (fn []
           (set-size (if (> size 276) 276 768))))

        props
        (mf/spread-props props
                         {:on-change-section on-change-section
                          :on-expand on-expand})]

    [:> (mf/provider muc/sidebar) {:value :right}
     [:aside
      {:class (stl/css-case :right-settings-bar true
                            :not-expand (not can-be-expanded?)
                            :expanded (> size 276))

       :id "right-sidebar-aside"
       :data-testid "right-sidebar"
       :data-size (str size)
       :style {"--width" (if can-be-expanded? (dm/str size "px") "276px")}}

      (when can-be-expanded?
        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-pointer-down
               :on-lost-pointer-capture on-lost-pointer-capture
               :on-pointer-move on-pointer-move}])

      [:> right-header*
       {:file file
        :layout layout
        :page-id page-id}]

      [:div {:class (stl/css :settings-bar-inside)}
       (cond
         dbg-shape-panel?
         [:& debug-shape-info]

         is-comments?
         [:> comments-sidebar* {}]

         is-history?
         (let [history-tab
               (mf/html
                [:article {:class (stl/css :history-tab)}
                 [:> history-toolbox*]])

               versions-tab
               (mf/html
                [:article {:class (stl/css :versions-tab)}
                 [:> versions-toolbox*]])

               button
               (mf/html
                [:> icon-button* {:variant "ghost"
                                  :aria-label (tr "labels.close")
                                  :on-click on-close-history
                                  :icon "close"}])]


           (let [tabs (mf/object
                       [{:label (tr "workspace.versions.tab.history")
                         :id "history"
                         :content versions-tab}
                        {:label (tr "workspace.versions.tab.actions")
                         :id "actions"
                         :content history-tab}])]
             [:> tab-switcher*
              {:tabs tabs
               :default-selected "history"
               :class (stl/css :left-sidebar-tabs)
               :action-button-position "end"
               :action-button button}]))

         :else
         [:> options-toolbox* props])]]]))
