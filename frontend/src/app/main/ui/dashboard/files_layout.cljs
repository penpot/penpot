;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.dashboard.files-layout
  "Reactive, persisted preference for how dashboard files are laid out
  (as a grid of thumbnails or as a compact list). The preference is shared
  between the team (projects) view and the project (files) view."
  (:require
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.i18n :refer [tr]]
   [app.util.storage :as storage]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private storage-key ::layout)

(def default-layout :grid)

;; Reactive ref with the current dashboard files layout (`:grid` or `:list`),
;; seeded from the persisted preference.
(defonce ref
  (l/atom (get storage/global storage-key default-layout)))

(defn set-layout!
  [layout]
  (reset! ref layout)
  (swap! storage/global assoc storage-key layout))

;; A radio toggle to switch the dashboard files layout between the grid of
;; thumbnails and a compact list. Self-contained: reads and writes the shared
;; persisted preference.
(mf/defc files-layout-toggle*
  []
  (let [layout    (mf/deref ref)

        on-change (mf/use-fn
                   (fn [value]
                     (set-layout! (keyword value))))]

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
                                   :label (tr "dashboard.files-layout.grid")}]}]))
