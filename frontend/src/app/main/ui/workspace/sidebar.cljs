;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.constants :refer [sidebar-default-width sidebar-default-max-width]]
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
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.workspace.comments :refer [comments-sidebar*]]
   [app.main.ui.workspace.left-header :refer [left-header*]]
   [app.main.ui.workspace.right-header :refer [right-header*]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox*]]
   [app.main.ui.workspace.sidebar.debug :refer [debug-panel*]]
   [app.main.ui.workspace.sidebar.debug-shape-info :refer [debug-shape-info*]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox*]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox*]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox*]]
   [app.main.ui.workspace.sidebar.shortcuts :refer [shortcuts-container*]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap*]]
   [app.main.ui.workspace.sidebar.versions :refer [versions-toolbox*]]
   [app.main.ui.workspace.tokens.sidebar :refer [tokens-sidebar-tab*]]
   [app.util.debug :as dbg]
   [app.util.i18n :refer [tr]]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;; --- Left Sidebar (Component)

(defn- on-collapse-left-sidebar
  []
  (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))

(mf/defc collapse-button*
  []
  ;; NOTE: This custom button may be replace by an action button when this variant is designed
  [:button {:class (stl/css :collapse-sidebar-button)
            :on-click on-collapse-left-sidebar}
   [:> icon* {:icon-id "arrow"
              :size "s"
              :aria-label (tr "workspace.sidebar.collapse")}]])

(mf/defc layers-content*
  {::mf/private true
   ::mf/memo true}
  [{:keys [width layout]}]
  (let [{on-pointer-down :on-pointer-down
         on-lost-pointer-capture :on-lost-pointer-capture
         on-pointer-move :on-pointer-move
         height :size}
        (use-resize-hook :sitemap 200 38 "0.6" :y false nil)

        sitemap-collapsed*
        (hooks/use-persisted-state ::sitemap-collapsed false)

        sitemap-collapsed?
        (deref sitemap-collapsed*)

        on-toggle-sitemap-collapsed
        (mf/use-fn #(reset! sitemap-collapsed* not))

        sitemap-height
        (if sitemap-collapsed? 32 height)]

    [:article {:class (stl/css :layers-tab)
               :style {:--height (dm/str height "px")}}

     [:> sitemap* {:layout layout
                   :height sitemap-height
                   :collapsed sitemap-collapsed?
                   :on-toggle-collapsed on-toggle-sitemap-collapsed}]

     (when-not ^boolean sitemap-collapsed?
       [:div {:class (stl/css :resize-area-horiz)
              :on-pointer-down on-pointer-down
              :on-lost-pointer-capture on-lost-pointer-capture
              :on-pointer-move on-pointer-move}

        [:div {:class (stl/css :resize-handle-horiz)}]])

     [:> layers-toolbox* {:size-parent width}]]))

(mf/defc left-sidebar*
  {::mf/memo true}
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
         width :size}
        (use-resize-hook :left-sidebar 318 318 500 :x false :left)

        on-tab-change
        (mf/use-fn
         (fn [id]
           (st/emit! (dcm/go-to-workspace :layout (keyword id)))
           (when (= id "tokens")
             (st/emit! (ptk/event ::ev/event {::ev/name "open-tokens-tab"})))))

        tabs
        (mf/with-memo [mode-inspect? design-tokens?]
          (if ^boolean mode-inspect?
            [{:label (tr "workspace.sidebar.layers")
              :id "layers"}]
            (if ^boolean design-tokens?
              [{:label (tr "workspace.sidebar.layers")
                :id "layers"}
               {:label (tr "workspace.toolbar.assets")
                :id "assets"}
               {:label "Tokens"
                :id "tokens"}]
              [{:label (tr "workspace.sidebar.layers")
                :id "layers"}
               {:label (tr "workspace.toolbar.assets")
                :id "assets"}])))

        aside-class
        (stl/css-case
         :left-settings-bar true
         :global/two-row    (<= width 300)
         :global/three-row  (and (> width 300) (<= width 400))
         :global/four-row  (> width 400))

        tabs-action-button
        (mf/with-memo []
          (mf/html [:> collapse-button* {}]))]

    [:> (mf/provider muc/sidebar) {:value :left}
     [:aside {:ref parent-ref
              :id "left-sidebar-aside"
              :data-testid "left-sidebar"
              :data-size (str width)
              :class aside-class
              :style {:--width (dm/str width "px")}}

      [:> left-header*
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
        [:> shortcuts-container* {:class (stl/css :settings-bar-content)}]

        (true? show-debug?)
        [:> debug-panel* {:class (stl/css :settings-bar-content)}]

        :else
        [:div {:class (stl/css  :settings-bar-content)}
         [:> tab-switcher* {:tabs tabs
                            :default "layers"
                            :selected (name section)
                            :on-change on-tab-change
                            :class (stl/css :left-sidebar-tabs)
                            :action-button-position "start"
                            :action-button tabs-action-button}

          (case section
            :assets
            [:> assets-toolbox*
             {:size (- width  58)
              :file-id file-id}]

            :tokens
            [:> tokens-sidebar-tab*]

            :layers
            [:> layers-content*
             {:layout layout
              :width width}])]])]]))

;; --- Right Sidebar (Component)

(defn- on-close-document-history
  []
  (st/emit! (dw/remove-layout-flag :document-history)))

(mf/defc history-content*
  {::mf/private true
   ::mf/memo true}
  []
  (let [selected*
        (hooks/use-persisted-state ::history-sidebar "history")

        selected
        (deref selected*)

        on-change-tab
        (mf/use-fn #(reset! selected* %))

        tabs
        (mf/with-memo []
          [{:label (tr "workspace.versions.tab.history")
            :id "history"}
           {:label (tr "workspace.versions.tab.actions")
            :id "actions"}])

        button
        (mf/with-memo []
          (mf/html
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "labels.close")
                             :on-click on-close-document-history
                             :icon "close"}]))]

    [:> tab-switcher* {:tabs tabs
                       :selected selected
                       :on-change on-change-tab
                       :class (stl/css :left-sidebar-tabs)
                       :action-button-position "end"
                       :action-button button}

     (case selected
       "history"
       [:article {:class (stl/css :history-tab)}
        [:> versions-toolbox* {}]]

       "actions"
       [:article {:class (stl/css :versions-tab)}
        [:> history-toolbox*]])]))

(mf/defc right-sidebar*
  {::mf/memo true}
  [{:keys [layout section file page-id drawing-tool] :as props}]
  (let [is-comments?     (= drawing-tool :comments)
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

        {on-pointer-down :on-pointer-down
         on-lost-pointer-capture :on-lost-pointer-capture
         on-pointer-move :on-pointer-move
         set-width :set-size
         width :size}
        (use-resize-hook :code sidebar-default-width sidebar-default-width sidebar-default-max-width :x true :right)

        on-change-section
        (mf/use-fn #(reset! current-section* %))

        on-expand
        (mf/use-fn
         (mf/deps width set-width)
         (fn []
           (set-width (if (> width sidebar-default-width)
                        sidebar-default-width
                        sidebar-default-max-width))))]

    [:> (mf/provider muc/sidebar) {:value :right}
     [:aside
      {:class (stl/css-case :right-settings-bar true
                            :not-expand (not can-be-expanded?)
                            :expanded (> width sidebar-default-width))

       :id "right-sidebar-aside"
       :data-testid "right-sidebar"
       :data-size (str width)
       :style {:--width (if can-be-expanded?
                          (dm/str width "px")
                          (dm/str sidebar-default-width "px"))}}

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
         [:> debug-shape-info*]

         is-comments?
         [:> comments-sidebar* {}]

         is-history?
         [:> history-content* {}]

         :else
         (let [props (mf/spread-props props
                                      {:on-change-section on-change-section
                                       :on-expand on-expand})]
           [:> options-toolbox* props]))]]]))
