;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.delete-account
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.core :as rx]
   [rumext.v2 :as mf]))

(defn on-error
  [{:keys [code] :as error}]
  (if (= :owner-teams-with-people code)
    (let [msg (tr "notifications.profile-deletion-not-allowed")]
      (rx/of (dm/error msg)))
    (rx/throw error)))

(mf/defc delete-account-modal
  {::mf/register modal/components
   ::mf/register-as :delete-account}
  []
  (let [on-close
        (mf/use-callback #(st/emit! (modal/hide)))

        on-accept
        (mf/use-callback
         #(st/emit! (modal/hide)
                    (du/request-account-deletion
                      (with-meta {} {:on-error on-error}))))]

    [:div.modal-overlay
     [:div.modal-container.change-email-modal
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (tr "modals.delete-account.title")]]
       [:div.modal-close-button
        {:on-click on-close} i/close]]

      [:div.modal-content
       [:& msgs/inline-banner
        {:type :warning
         :content (tr "modals.delete-account.info")}]]

      [:div.modal-footer
       [:div.action-buttons
        [:button.btn-warning.btn-large {:on-click on-accept
                                        :data-test "delete-account-btn"}
         (tr "modals.delete-account.confirm")]
        [:button.btn-secondary.btn-large {:on-click on-close}
         (tr "modals.delete-account.cancel")]]]]]))

