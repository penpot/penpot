;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.workspace.sidebar.drawtools :refer [draw-toolbox]]
   [uxbox.main.ui.workspace.sidebar.history :refer [history-toolbox]]
   [uxbox.main.ui.workspace.sidebar.icons :refer [icons-toolbox]]
   [uxbox.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
   [uxbox.main.ui.workspace.sidebar.options :refer [options-toolbox]]
   [uxbox.main.ui.workspace.sidebar.sitemap :refer [sitemap-toolbox]]
   [uxbox.util.rdnd :as rdnd]))

;; --- Left Sidebar (Component)

(mf/defc left-sidebar
  {:wrap [mf/wrap-memo]}
  [{:keys [flags page] :as props}]
  [:aside#settings-bar.settings-bar.settings-bar-left
   [:> rdnd/provider {:backend rdnd/html5}
    [:div.settings-bar-inside
     (when (contains? flags :sitemap)
       [:& sitemap-toolbox {:project-id (:project page)
                            :current-page-id (:id page)
                            :page page}])
     #_(when (contains? flags :document-history)
         (history-toolbox page-id))
     (when (contains? flags :layers)
       [:& layers-toolbox {:page page}])]]])

;; --- Right Sidebar (Component)

(mf/defc right-sidebar
  [{:keys [flags page] :as props}]
  [:aside#settings-bar.settings-bar
   [:div.settings-bar-inside
    (when (contains? flags :drawtools)
      [:& draw-toolbox {:flags flags}])
    (when (contains? flags :element-options)
      [:& options-toolbox {:page page}])
    (when (contains? flags :icons)
      #_(icons-toolbox))]])

