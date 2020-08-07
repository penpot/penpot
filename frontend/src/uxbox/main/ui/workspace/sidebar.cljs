;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [uxbox.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [uxbox.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [uxbox.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [uxbox.main.ui.workspace.sidebar.sitemap :refer [sitemap-toolbox]]
   [uxbox.main.ui.workspace.sidebar.assets :refer [assets-toolbox]]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {:wrap [mf/memo]}
  [{:keys [layout page file] :as props}]
  [:aside.settings-bar.settings-bar-left
   [:div.settings-bar-inside
    {:data-layout (str/join "," layout)}
    (when (contains? layout :sitemap)
      [:& sitemap-toolbox {:file file
                           :page page
                           :layout layout}])
    (when (contains? layout :document-history)
      [:& history-toolbox])
    (when (contains? layout :layers)
      [:& layers-toolbox {:page page}])
    (when (contains? layout :assets)
      [:& assets-toolbox])]])

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  [{:keys [layout page local] :as props}]
  [:aside#settings-bar.settings-bar
   [:div.settings-bar-inside
    (when (contains? layout :element-options)
      [:& options-toolbox {:page page
                           :local local}])]])
