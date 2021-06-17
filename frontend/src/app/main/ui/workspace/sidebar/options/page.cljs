;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc options
  {::mf/wrap [mf/memo]}
  []
  (let [options (mf/deref refs/workspace-page-options)

        on-change
        (fn [value]
          (st/emit! (dw/change-canvas-color value)))

        on-open
        (fn []
          (st/emit! (dwu/start-undo-transaction)))

        on-close
        (fn []
          (st/emit! (dwu/commit-undo-transaction)))]

    [:div.element-set
     [:div.element-set-title (tr "workspace.options.canvas-background")]
     [:div.element-set-content
      [:& color-row {:disable-gradient true
                     :disable-opacity true
                     :color {:color (get options :background "#E8E9EA")
                             :opacity 1}
                     :on-change on-change
                     :on-open on-open
                     :on-close on-close}]]]))

