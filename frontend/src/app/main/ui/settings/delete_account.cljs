;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.delete-account
  (:require-macros [app.main.style :as stl])
  (:require
   [app.config :as cf]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.org-avatar :refer [org-avatar*]]
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
  (let [orgs* (mf/use-state nil)
        orgs  (deref orgs*)
        has-orgs? (seq orgs)

        expanded* (mf/use-state true)
        expanded? (deref expanded*)
        on-toggle (mf/use-fn #(swap! expanded* not))

        on-accept
        (mf/use-fn
         #(st/emit! (modal/hide)
                    (du/request-account-deletion
                     (with-meta {} {:on-error on-error}))))]

    (mf/with-effect []
      (if (contains? cf/flags :nitrate)
        (let [sub (->> (rp/cmd! :get-owned-organizations-summary {})
                       (rx/subs!
                        (fn [result] (reset! orgs* (or result [])))
                        (fn [_] (reset! orgs* []))))]
          (fn []
            (rx/dispose! sub)))
        (reset! orgs* [])))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}

      [:div {:class (stl/css :modal-header)}
       [:h2 {:class (stl/css :modal-title)} (tr "modals.delete-account.title")]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} deprecated-icon/close]]

      [:div {:class (stl/css :modal-content)}
       [:& context-notification
        {:level :warning
         :content (tr (if has-orgs?
                        "modals.delete-account.info.with-orgs"
                        "modals.delete-account.info"))}]

       (when has-orgs?
         [:div {:class (stl/css :orgs-section)}
          [:button {:class (stl/css :orgs-section-toggle)
                    :type "button"
                    :aria-expanded expanded?
                    :on-click on-toggle}
           [:span {:class (stl/css :orgs-section-title)}
            (tr "modals.delete-account.owned-orgs.list-title")]
           [:> icon* {:icon-id i/arrow
                      :size "s"
                      :class (stl/css-case :orgs-section-arrow true
                                           :expanded expanded?)}]]
          (when expanded?
            [:ul {:class (stl/css :org-list)}
             (for [{:keys [id name team-count member-count] :as org} orgs]
               [:li {:class (stl/css :org-item) :key id}
                [:> org-avatar* {:org org :size "xxl"}]
                [:div {:class (stl/css :org-info)}
                 [:span {:class (stl/css :org-name)} name]
                 [:div {:class (stl/css :org-counts)}
                  [:span (tr "modals.delete-account.owned-orgs.teams-count"
                             (i18n/c (or team-count 0)))]
                  [:span (tr "modals.delete-account.owned-orgs.members-count"
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
