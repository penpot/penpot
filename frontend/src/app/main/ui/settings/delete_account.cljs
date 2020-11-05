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
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(defn on-error
  [{:keys [code] :as error}]
  (if (= :owner-teams-with-people code)
    (let [msg (tr "notifications.profile-deletion-not-allowed")]
      (rx/of (dm/error msg)))
    (rx/throw error)))

(defn on-success
  [x]
  (st/emit! (rt/nav :auth-goodbye)))

(mf/defc delete-account-modal
  {::mf/register modal/components
   ::mf/register-as :delete-account}
  [props]
  (let [locale (mf/deref i18n/locale)
        on-close
        (mf/use-callback (st/emitf (modal/hide)))

        on-accept
        (mf/use-callback
         (st/emitf (modal/hide)
                   (da/request-account-deletion
                    (with-meta {} {:on-error on-error
                                   :on-success on-success}))))]

    [:div.modal-overlay
     [:div.modal-container.change-email-modal
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (t locale "modals.delete-account.title")]]
       [:div.modal-close-button
        {:on-click on-close} i/close]]

      [:div.modal-content
       [:& msgs/inline-banner
        {:type :warning
         :content (t locale "modals.delete-account.info")}]]

      [:div.modal-footer
       [:div.action-buttons
        [:button.btn-warning.btn-large {:on-click on-accept}
         (t locale "modals.delete-account.confirm")]
        [:button.btn-secondary.btn-large {:on-click on-close}
         (t locale "modals.delete-account.cancel")]]]]]))

