;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar
  (:require [uxbox.main.refs :as refs]
            [uxbox.main.ui.workspace.sidebar.options :refer [options-toolbox]]
            [uxbox.main.ui.workspace.sidebar.layers :refer [layers-toolbox]]
            [uxbox.main.ui.workspace.sidebar.sitemap :refer [sitemap-toolbox]]
            [uxbox.main.ui.workspace.sidebar.history :refer [history-toolbox]]
            [uxbox.main.ui.workspace.sidebar.icons :refer [icons-toolbox]]
            [uxbox.main.ui.workspace.sidebar.drawtools :refer [draw-toolbox]]
            [uxbox.util.mixins :as mx :include-macros true]))

;; --- Left Sidebar (Component)

(mx/defc left-sidebar
  {:mixins [mx/static]}
  [flags page-id]
  [:aside#settings-bar.settings-bar.settings-bar-left
   [:div.settings-bar-inside
    (when (contains? flags :sitemap)
      (sitemap-toolbox page-id))
    (when (contains? flags :document-history)
      (history-toolbox page-id))
    (when (contains? flags :layers)
      (layers-toolbox))]])

;; --- Right Sidebar (Component)

(mx/defc right-sidebar
  {:mixins [mx/static]}
  [flags page-id]
  [:aside#settings-bar.settings-bar
   [:div.settings-bar-inside
    (when (contains? flags :drawtools)
      (draw-toolbox flags))
    (when (contains? flags :element-options)
      (options-toolbox))
    (when (contains? flags :icons)
      (icons-toolbox))]])

