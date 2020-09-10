;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.settings.delete-account
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [app.main.data.auth :as da]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.modal :as modal]
   [app.util.i18n :as i18n :refer [tr t]]))

(mf/defc delete-account-modal
  {::mf/register modal/components
   ::mf/register-as :delete-account}
  [props]
  (let [locale (mf/deref i18n/locale)]
    [:div.modal-overlay
     [:section.generic-modal.change-email-modal
      [:span.close {:on-click #(modal/hide!)} i/close]

      [:section.modal-content.generic-form
       [:h2 (t locale "settings.delete-account-title")]

       [:& msgs/inline-banner
        {:type :warning
         :content (t locale "settings.delete-account-info")}]

       [:div.button-row
        [:button.btn-warning.btn-large
         {:on-click #(do
                       (modal/hide!)
                       (st/emit! da/request-account-deletion))}
         (t locale "settings.yes-delete-my-account")]
        [:button.btn-secondary.btn-large
         {:on-click #(modal/hide!)}
         (t locale "settings.cancel-and-keep-my-account")]]]]]))
