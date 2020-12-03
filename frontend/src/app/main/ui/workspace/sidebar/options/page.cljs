;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.util.i18n :as i18n :refer [t]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]))

(defn use-change-color [page-id]
  (mf/use-callback
   (mf/deps page-id)
   (fn [value]
     (st/emit! (dw/change-canvas-color value)))))

(mf/defc options
  [{:keys [page-id] :as props}]
  (let [locale (i18n/use-locale)
        options (mf/deref refs/workspace-page-options)
        handle-change-color (use-change-color page-id)

        on-open
        (mf/use-callback
         (mf/deps page-id)
         #(st/emit! (dwc/start-undo-transaction)))

        on-close
        (mf/use-callback
         (mf/deps page-id)
         #(st/emit! (dwc/commit-undo-transaction)))]

    [:div.element-set
     [:div.element-set-title (t locale "workspace.options.canvas-background")]
     [:div.element-set-content
      [:& color-row {:disable-gradient true
                     :disable-opacity true
                     :color {:color (get options :background "#E8E9EA")
                             :opacity 1}
                     :on-change handle-change-color
                     :on-open on-open
                     :on-close on-close}]]]))

