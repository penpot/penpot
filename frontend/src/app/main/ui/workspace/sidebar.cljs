;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar
  (:require
   [app.main.refs :as refs]
   [app.main.ui.workspace.comments :refer [comments-sidebar]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap]]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {:wrap [mf/memo]}
  [{:keys [layout ] :as props}]
  [:aside.settings-bar.settings-bar-left
   [:div.settings-bar-inside
    {:data-layout (str/join "," layout)}
    (when (contains? layout :layers)
      [:*
       [:& sitemap {:layout layout}]
       [:& layers-toolbox]])

    (when (contains? layout :document-history)
      [:& history-toolbox])

    (when (contains? layout :assets)
      [:& assets-toolbox])]])

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  [{:keys [local] :as props}]
  (let [drawing-tool (:tool (mf/deref refs/workspace-drawing))]
    [:aside.settings-bar
     [:div.settings-bar-inside
      (if (= drawing-tool :comments)
        [:& comments-sidebar]
        [:& options-toolbox {:local local}])]]))
