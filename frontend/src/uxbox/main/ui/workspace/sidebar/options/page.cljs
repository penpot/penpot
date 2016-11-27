;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require [lentes.core :as l]
            [uxbox.util.i18n :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.state :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.ui.workspace.colorpicker :refer (colorpicker)]
            [uxbox.main.ui.workspace.recent-colors :refer (recent-colors)]
            [uxbox.main.geom :as geom]
            [uxbox.util.dom :as dom]))


(mx/defc measures-menu
  {:mixins [mx/static]}
  [menu]
  [:div.element-set
   [:div.element-set-title (:name menu)]
   [:div.element-set-content
    [:span "Size"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text {:type "number" :placeholder "x"}]]
     [:div.input-element.pixels
      [:input.input-text {:type "number" :placeholder "y"}]]]
    [:span "Background color"]]])

(mx/defc grid-options-menu
  {:mixins [mx/static]}
  [menu]
  [:div.element-set
   [:div.element-set-title (:name menu)]
   [:div.element-set-content
    [:strong "Content here"]]])
