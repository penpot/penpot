;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar
  (:require
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.comments :refer [comments-sidebar]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [app.main.ui.workspace.sidebar.shortcuts :refer [shortcuts-container]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {:wrap [mf/memo]}
  [{:keys [layout] :as props}]
  (let [section    (cond (contains? layout :layers) :layers
                         (contains? layout :assets) :assets)
        shortcuts? (contains? layout :shortcuts)

        {:keys [on-pointer-down on-lost-pointer-capture on-mouse-move parent-ref size]}
        (use-resize-hook :left-sidebar 255 255 500 :x false :left)

        handle-collapse
        (fn []
          (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))]

    [:aside.settings-bar.settings-bar-left {:ref parent-ref
                                            :class (dom/classnames
                                                    :two-row   (<= size 300)
                                                    :three-row (and (> size 300) (<= size 400))
                                                    :four-row  (> size 400))
                                            :style #js {"--width" (str size "px")}}
     [:div.resize-area {:on-pointer-down on-pointer-down
                        :on-lost-pointer-capture on-lost-pointer-capture
                        :on-mouse-move on-mouse-move}]

     [:div.settings-bar-inside

      [:* (if shortcuts?
            [:& shortcuts-container]
            [:*
             [:button.collapse-sidebar
              {:on-click handle-collapse}
              i/arrow-slide]
             [:& tab-container {:on-change-tab #(st/emit! (dw/go-to-layout %))
                                :selected section
                                :shortcuts? shortcuts?}

              [:& tab-element {:id :layers :title (tr "workspace.sidebar.layers")}
               [:div.layers-tab
                [:& sitemap {:layout layout}]
                [:& layers-toolbox]]]

              [:& tab-element {:id :assets :title (tr "workspace.toolbar.assets")}
               [:& assets-toolbox]]]])]]]))

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [props]
  (let [layout (obj/get props "layout")
        drawing-tool (:tool (mf/deref refs/workspace-drawing))]

    [:aside.settings-bar.settings-bar-right
     [:div.settings-bar-inside
      (cond
        (= drawing-tool :comments)
        [:& comments-sidebar]

        (contains? layout :document-history)
        [:& history-toolbox]

        :else
        [:> options-toolbox props])]]))

