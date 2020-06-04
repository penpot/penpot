;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.page
  "Page options menu entries."
  (:require
   [rumext.alpha :as mf]
   [okulary.core :as l]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.data.workspace :as dw]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row]]))

(def options-iref
  (l/derived :options refs/workspace-data))

(defn use-change-color [page]
  (mf/use-callback
   (mf/deps page)
   (fn [value]
     (st/emit! (dw/change-canvas-color value)))))

(mf/defc options
  [{:keys [page] :as props}]

  (let [locale (i18n/use-locale)
        options (mf/deref refs/workspace-page-options)
        handle-change-color (use-change-color page)]
    [:div.element-set
     [:div.element-set-title (t locale "workspace.options.canvas_background")]
     [:div.element-set-content
      [:& color-row {:disable-opacity true
                     :value {:value (get options :background "#E8E9EA")
                             :opacity 1}
                     :on-change handle-change-color}]]])
  )

