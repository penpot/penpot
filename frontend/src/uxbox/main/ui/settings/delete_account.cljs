;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.delete-account
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.users :as du]
   [uxbox.main.store :as st]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.messages :as msgs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(mf/defc delete-account-modal
  [props]
  (let [locale (mf/deref i18n/locale)]
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
        (t locale "settings.cancel-and-keep-my-account")]]]]))
