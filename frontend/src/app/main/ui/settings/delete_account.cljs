;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.delete-account
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.main.ui.notifications.context-notification :refer [context-notification]]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn on-error
  [cause]
  (let [code (-> cause ex-data :code)]
    (if (= :owner-teams-with-people code)
      (let [msg (tr "notifications.profile-deletion-not-allowed")]
        (rx/of (ntf/error msg)))
      (rx/throw cause))))

(mf/defc delete-account-modal
  {::mf/register modal/components
   ::mf/register-as :delete-account}
  []
  (let [on-accept
        (mf/use-fn
         #(st/emit! (modal/hide)
                    (du/request-account-deletion
                     (with-meta {} {:on-error on-error}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}

      [:div {:class (stl/css :modal-header)}

       [:h2 {:class (stl/css :modal-title)} (tr "modals.delete-account.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} i/close]]

      [:div {:class (stl/css :modal-content)}
       [:& context-notification
        {:level :warning
         :content (tr "modals.delete-account.info")}]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:button {:class (stl/css :cancel-button)
                  :on-click modal/hide!}
         (tr "modals.delete-account.cancel")]
        [:button {:class (stl/css-case :accept-button true
                                       :danger true)
                  :on-click on-accept
                  :data-testid "delete-account-btn"}
         (tr "modals.delete-account.confirm")]]]]]))

