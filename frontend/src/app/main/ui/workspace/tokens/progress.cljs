;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.progress
  "Dialog that blocks the workspace while the shapes are set to the values
  of their tokens (`app.main.data.workspace.tokens.progress`)."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private progress-ref
  (l/derived ::dwtpr/progress st/state))

(mf/defc progress-dialog*
  {::mf/private true}
  []
  ;; No shortcut works while the dialog is open
  (hooks/use-shortcuts ::token-progress {} nil)
  [:div {:class (stl/css :overlay)}
   [:div {:class (stl/css :dialog)
          :role "alertdialog"
          :aria-modal true
          :aria-busy true
          :aria-labelledby "token-progress-title"}
    [:div {:id "token-progress-title"
           :class (stl/css :title)}
     (tr "workspace.tokens.updating-values")]
    [:div {:class (stl/css :bar)
           :role "progressbar"
           :aria-labelledby "token-progress-title"}
     [:div {:class (stl/css :bar-fill)}]]]])

(mf/defc token-progress*
  []
  (when (mf/deref progress-ref)
    [:> progress-dialog*]))
