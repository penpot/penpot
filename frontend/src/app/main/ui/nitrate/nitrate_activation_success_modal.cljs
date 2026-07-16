;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.nitrate.nitrate-activation-success-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.time :as ct]
   [app.main.data.modal :as modal]
   [app.main.data.nitrate :as dnt]
   [app.main.refs :as refs]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.ds.foundations.assets.raw-svg :refer [raw-svg*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc nitrate-activation-success-modal*
  {::mf/register modal/components
   ::mf/register-as :nitrate-activation-success}
  []

  (let [profile         (mf/deref refs/profile)
        light?          (= "light" (:theme profile))
        svg-id          (if light? "logo-subscription-light" "logo-subscription")

        nitrate-license (:subscription profile)
        cancel-at       (:cancel-at nitrate-license)
        manual?         (:manual nitrate-license)
        date-str        (when cancel-at
                          (ct/format-inst cancel-at "d MMMM, yyyy"))

        on-create-org
        (mf/use-fn
         (fn []
           (modal/hide!)
           (dnt/go-to-nitrate-ac-create-organization)))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click modal/hide!}
       [:> icon* {:icon-id "close"
                  :size "m"}]]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-start)}
        [:> raw-svg* {:id svg-id}]]

       [:div {:class (stl/css :modal-end)}
        [:div {:class (stl/css :modal-title)}
         (tr "nitrate.activation-success.title")]

        (when (and manual? date-str)
          [:p {:class (stl/css :modal-text-primary)}
           (tr "nitrate.activation-success.active-until" date-str)])

        [:p {:class (stl/css :modal-text)}
         (tr "nitrate.activation-success.manage-info")]

        [:p {:class (stl/css :modal-text)}
         (tr "nitrate.activation-success.enjoy")]

        [:> button* {:variant "primary"
                     :on-click on-create-org
                     :class (stl/css :modal-button)}
         (tr "nitrate.activation-success.create-org")]]]]]))
