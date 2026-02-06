;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.nitrate.nitrate-form
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

;; FIXME: rename to `form` (remove the nitrate prefix from namespace,
;; because it is already under nitrate)

(mf/defc nitrate-form-modal*
  {::mf/register modal/components
   ::mf/register-as :nitrate-form}
  []
  (let [on-click
        (mf/use-fn
         (fn []
           (dom/open-new-window "/control-center/licenses/start")))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :nitrate-form)}

       [:div {:class (stl/css :modal-header)}
        [:h2 {:class (stl/css :modal-title)}
         "BUY NITRATE"]

        [:button {:class (stl/css :modal-close-btn)
                  :on-click modal/hide!} deprecated-icon/close]]

       [:div {:class (stl/css :modal-content)}
        "Nitrate is so cool! You should buy it!"]

       [:div {:class (stl/css :modal-footer)}
        [:div {:class (stl/css :action-buttons)}
         [:> button* {:variant "primary"
                      :on-click on-click}
          "BUY NOW!"]]]]]]))


