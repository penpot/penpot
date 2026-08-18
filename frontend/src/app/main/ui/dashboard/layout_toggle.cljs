;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.layout-toggle
  "Reactive, persisted preference for how dashboard files are laid out
  (as a grid of thumbnails or as a compact list). The preference is shared
  between the team (projects) view and the project (files) view."
  (:require
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layout-key ::dashboard-layout)
(def default-layout :grid)

(mf/defc layout-toggle*
  [{:keys [layout on-change]}]
  [:> radio-buttons* {:selected (name layout)
                      :on-change on-change
                      :name "dashboard-files-layout"
                      :options [{:id "dashboard-files-layout-list"
                                 :value "list"
                                 :icon i/view-as-list
                                 :label (tr "dashboard.files-layout.list")}
                                {:id "dashboard-files-layout-grid"
                                 :value "grid"
                                 :icon i/view-as-icons
                                 :label (tr "dashboard.files-layout.grid")}]}])
