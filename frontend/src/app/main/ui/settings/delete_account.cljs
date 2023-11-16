;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.delete-account
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
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
  (let [new-css-system  (mf/use-ctx ctx/new-css-system)
        on-close
        (mf/use-callback #(st/emit! (modal/hide)))

        on-accept
        (mf/use-callback
         #(st/emit! (modal/hide)
                    (du/request-account-deletion
                     (with-meta {} {:on-error on-error}))))]

    (if new-css-system
      [:div {:class (stl/css :modal-overlay)}
       [:div {:class (stl/css :modal-container)}

        [:div {:class (stl/css :modal-header)}

         [:h2 {:class (stl/css :modal-title)} (tr "modals.delete-account.title")]
         [:button {:class (stl/css :modal-close-btn)
                   :on-click on-close} i/close-refactor]]

        [:div {:class (stl/css :modal-content)}
         [:& msgs/inline-banner
          {:type :warning
           :content (tr "modals.delete-account.info")}]]

        [:div {:class (stl/css :modal-footer)}
         [:div {:class (stl/css :action-buttons)}
          [:button {:class (stl/css :cancel-button)
                    :on-click on-close}
           (tr "modals.delete-account.cancel")]
          [:button {:class (stl/css-case :accept-button true
                                         :danger true)
                    :on-click on-accept
                    :data-test "delete-account-btn"}
           (tr "modals.delete-account.confirm")]]]]]



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
          [:button.btn-danger.btn-large {:on-click on-accept
                                         :data-test "delete-account-btn"}
           (tr "modals.delete-account.confirm")]
          [:button.btn-secondary.btn-large {:on-click on-close}
           (tr "modals.delete-account.cancel")]]]]])))

