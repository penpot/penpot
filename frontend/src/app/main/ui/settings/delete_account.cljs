;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.settings.delete-account
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.organization-avatar :refer [organization-avatar*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.icons :as deprecated-icon]
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
  (let [organizations* (mf/use-state nil)
        organizations  (deref organizations*)
        has-organizations? (seq organizations)

        expanded* (mf/use-state true)
        expanded? (deref expanded*)
        on-toggle (mf/use-fn #(swap! expanded* not))

        on-accept
        (mf/use-fn
         #(st/emit! (modal/hide)
                    (du/request-account-deletion
                     (with-meta {} {:on-error on-error}))))]

    (mf/with-effect []
      (if (contains? cf/flags :admin-console)
        (let [sub (->> (rp/cmd! :get-owned-organizations-summary {})
                       (rx/subs!
                        (fn [result] (reset! organizations* (or result [])))
                        (fn [_] (reset! organizations* []))))]
          (fn []
            (rx/dispose! sub)))
        (reset! organizations* [])))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}

      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.delete-account.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :warning-notice)}
        [:& context-notification
         {:level :warning
          :content (tr (if has-organizations?
                         "modals.delete-account.info.with-organizations"
                         "modals.delete-account.info"))}]]

       (when has-organizations?
         [:div {:class (stl/css :organizations-section)}
          [:button {:class (stl/css :organizations-section-toggle)
                    :type "button"
                    :aria-expanded expanded?
                    :on-click on-toggle}
           [:span {:class (stl/css :organizations-section-title)}
            (tr "modals.delete-account.owned-organizations.list-title")]
           [:> icon* {:icon-id i/arrow
                      :size "s"
                      :class (stl/css-case :organizations-section-arrow true
                                           :expanded expanded?)}]]
          (when expanded?
            [:ul {:class (stl/css :organization-list)}
             (for [{:keys [id name team-count member-count] :as organization} organizations]
               [:li {:class (stl/css :organization-item) :key id}
                [:> organization-avatar* {:organization organization :size "xxl"}]
                [:div {:class (stl/css :organization-info)}
                 [:span {:class (stl/css :organization-name)} name]
                 [:div {:class (stl/css :organization-counts)}
                  [:span (tr "modals.delete-account.owned-organizations.teams-count"
                             (i18n/c (or team-count 0)))]
                  [:span (tr "modals.delete-account.owned-organizations.members-count"
                             (i18n/c (or member-count 0)))]]]])])])]

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
