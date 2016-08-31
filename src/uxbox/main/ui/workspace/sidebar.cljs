;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar
  (:require [lentes.core :as l]
            [uxbox.main.state :as st]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.sidebar.options :refer (options-toolbox)]
            [uxbox.main.ui.workspace.sidebar.layers :refer (layers-toolbox)]
            [uxbox.main.ui.workspace.sidebar.sitemap :refer (sitemap-toolbox)]
            [uxbox.main.ui.workspace.sidebar.history :refer (history-toolbox)]
            [uxbox.main.ui.workspace.sidebar.icons :refer (icons-toolbox)]
            [uxbox.main.ui.workspace.sidebar.drawtools :refer (draw-toolbox)]))

;; --- Left Sidebar (Component)

(mx/defc left-sidebar
  {:mixins [mx/reactive mx/static]}
  []
  (let [flags (mx/react wb/flags-ref)]
    [:aside#settings-bar.settings-bar.settings-bar-left
     [:div.settings-bar-inside
      (when (contains? flags :sitemap)
        (sitemap-toolbox))
      (when (contains? flags :document-history)
        (history-toolbox))
      (when (contains? flags :layers)
        (layers-toolbox))]]))

;; --- Right Sidebar (Component)

(mx/defc right-sidebar
  {:mixins [mx/reactive mx/static]}
  []
  (let [flags (mx/react wb/flags-ref)]
    [:aside#settings-bar.settings-bar
     [:div.settings-bar-inside
      (when (contains? flags :drawtools)
        (draw-toolbox))
      (when (contains? flags :element-options)
        (options-toolbox))
      (when (contains? flags :icons)
        (icons-toolbox))]]))

