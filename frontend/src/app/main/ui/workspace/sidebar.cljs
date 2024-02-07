;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.context :as muc]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.workspace.comments :refer [comments-sidebar]]
   [app.main.ui.workspace.left-header :refer [left-header]]
   [app.main.ui.workspace.right-header :refer [right-header]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]
   [app.main.ui.workspace.sidebar.debug :refer [debug-panel]]
   [app.main.ui.workspace.sidebar.debug-shape-info :refer [debug-shape-info]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [app.main.ui.workspace.sidebar.shortcuts :refer [shortcuts-container]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap]]
   [app.util.debug :as dbg]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [layout file page-id] :as props}]
  (let [options-mode   (mf/deref refs/options-mode-global)
        mode-inspect?  (= options-mode :inspect)
        project        (mf/deref refs/workspace-project)
        show-pages?    (mf/use-state true)
        toggle-pages   (mf/use-callback #(reset! show-pages? not))

        section        (cond (or mode-inspect? (contains? layout :layers)) :layers
                             (contains? layout :assets) :assets)

        shortcuts?     (contains? layout :shortcuts)
        show-debug?    (contains? layout :debug-panel)

        {on-pointer-down :on-pointer-down on-lost-pointer-capture :on-lost-pointer-capture  on-pointer-move :on-pointer-move parent-ref :parent-ref size :size}
        (use-resize-hook :left-sidebar 275 275 500 :x false :left)

        {on-pointer-down-pages :on-pointer-down on-lost-pointer-capture-pages  :on-lost-pointer-capture on-pointer-move-pages :on-pointer-move size-pages :size}
        (use-resize-hook :sitemap 200 38 400 :y false nil)


        handle-collapse
        (mf/use-fn #(st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))

        on-tab-change
        (mf/use-fn #(st/emit! (dw/go-to-layout %)))]

    [:& (mf/provider muc/sidebar) {:value :left}
     [:aside {:ref parent-ref
              :id "left-sidebar-aside"
              :data-size (str size)
              :class (stl/css-case :left-settings-bar true
                                   :global/two-row    (<= size 300)
                                   :global/three-row  (and (> size 300) (<= size 400))
                                   :global/four-row  (> size 400))
              :style #js {"--width" (dm/str size "px")}}

      [:& left-header {:file file :layout layout :project project :page-id page-id
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
         [:& tab-container
          {:on-change-tab on-tab-change
           :selected section
           :collapsable true
           :handle-collapse handle-collapse
           :header-class (stl/css :tab-spacing)}

          [:& tab-element {:id :layers
                           :title (tr "workspace.sidebar.layers")}
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
                     :on-pointer-move on-pointer-move-pages}])

            [:& layers-toolbox {:size-parent size
                                :size size-pages}]]]

          (when-not ^boolean mode-inspect?
            [:& tab-element {:id :assets
                             :title (tr "workspace.toolbar.assets")}
             [:& assets-toolbox {:size (- size 58)}]])]])]]))

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [layout section file page-id] :as props}]
  (let [drawing-tool     (:tool (mf/deref refs/workspace-drawing))

        is-comments?     (= drawing-tool :comments)
        is-history?      (contains? layout :document-history)
        is-inspect?      (= section :inspect)

        ;;expanded?      (mf/deref refs/inspect-expanded)
        ;;prev-expanded? (hooks/use-previous expanded?)

        current-section* (mf/use-state :info)
        current-section  (deref current-section*)

        can-be-expanded? (or (dbg/enabled? :shape-panel)
                             (and (not is-comments?)
                                  (not is-history?)
                                  is-inspect?
                                  (= current-section :code)))

        {:keys [on-pointer-down on-lost-pointer-capture on-pointer-move set-size size]}
        (use-resize-hook :code 276 276 768 :x true :right)

        handle-change-section
        (mf/use-callback
         (fn [section]
           (reset! current-section* section)))

        handle-expand
        (mf/use-callback
         (mf/deps size)
         (fn []
           (set-size (if (> size 276) 276 768))))

        props
        (-> props
            (obj/clone)
            (obj/set! "on-change-section" handle-change-section)
            (obj/set! "on-expand" handle-expand))]

    [:& (mf/provider muc/sidebar) {:value :right}
     [:aside {:class (stl/css-case :right-settings-bar true
                                   :not-expand (not can-be-expanded?)
                                   :expanded (> size 276))

              :id "right-sidebar-aside"
              :data-size (str size)
              :style #js {"--width" (when can-be-expanded? (dm/str size "px"))}}
      (when can-be-expanded?
        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-pointer-down
               :on-lost-pointer-capture on-lost-pointer-capture
               :on-pointer-move on-pointer-move}])
      [:& right-header {:file file :layout layout :page-id page-id}]

      [:div {:class (stl/css :settings-bar-inside)}
       (cond
         (dbg/enabled? :shape-panel)
         [:& debug-shape-info]

         (true? is-comments?)
         [:& comments-sidebar]

         (true? is-history?)
         [:& history-toolbox]

         :else
         [:> options-toolbox props])]]]))
