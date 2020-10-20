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
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.main.ui.workspace.comments :refer [comments-sidebar]]
   [app.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [app.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [app.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [app.main.ui.workspace.sidebar.sitemap :refer [sitemap]]
   [app.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {:wrap [mf/memo]}
  [{:keys [layout page-id file project] :as props}]
  [:aside.settings-bar.settings-bar-left
   [:div.settings-bar-inside
    {:data-layout (str/join "," layout)}
    (when (contains? layout :sitemap)
      [:& sitemap {:file file
                   :page-id page-id
                   :layout layout}])
    (when (contains? layout :document-history)
        [:& history-toolbox])
    (when (contains? layout :layers)
      [:& layers-toolbox])
    (when (contains? layout :assets)
      [:& assets-toolbox {:team-id (:team-id project)
                          :file file}])]])

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  [{:keys [layout page-id file-id local] :as props}]
  [:aside#settings-bar.settings-bar
   [:div.settings-bar-inside
    (when (contains? layout :element-options)
      [:& options-toolbox
       {:page-id page-id
        :file-id file-id
        :local local}])
    (when (contains? layout :comments)
      [:& comments-sidebar])]])
