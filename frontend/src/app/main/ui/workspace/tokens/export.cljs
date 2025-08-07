;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.export
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.workspace.tokens.export.modal :refer [export-modal-body*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc export-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/export}
  []
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-dialog)}
    [:> icon-button* {:class (stl/css :close-btn)
                      :on-click modal/hide!
                      :aria-label (tr "labels.close")
                      :variant "ghost"
                      :icon "close"}]
    [:> export-modal-body*]]])
