;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.icons :as i]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc modal
  {::mf/wrap-props false}
  [{:keys [] :as _args}]
  (let [handle-close-dialog (mf/use-callback #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} "Themes"]

      [:div {:class (stl/css :modal-content)}
       "Themes"]]]))
