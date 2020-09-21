;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.settings.change-email
  (:require
   [app.common.spec :as us]
   [app.main.data.auth :as da]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :refer [input submit-button form]]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.main.ui.modal :as modal]
   [app.util.i18n :as i18n :refer [tr t]]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(s/def ::email-1 ::us/email)
(s/def ::email-2 ::us/email)

(defn- email-equality
  [data]
  (let [email-1 (:email-1 data)
        email-2 (:email-2 data)]
    (cond-> {}
      (and email-1 email-2 (not= email-1 email-2))
      (assoc :email-2 {:message (tr "errors.email-invalid-confirmation")}))))

(s/def ::email-change-form
  (s/keys :req-un [::email-1 ::email-2]))

(defn- on-error
  [form error]
  (cond
    (= (:code error) :email-already-exists)
    (swap! form (fn [data]
                  (let [error {:message (tr "errors.email-already-exists")}]
                    (assoc-in data [:errors :email-1] error))))

    :else
    (let [msg (tr "errors.unexpected-error")]
      (st/emit! (dm/error msg)))))

(defn- on-success
  [profile data]
  (let [msg (tr "auth.notifications.validation-email-sent" (:email profile))]
    (st/emit! (dm/info msg) modal/hide)))

(defn- on-submit
  [profile form event]
  (let [data (with-meta {:email (get-in form [:clean-data :email-1])}
               {:on-error (partial on-error form)
                :on-success (partial on-success profile)})]
    (st/emit! (du/request-email-change data))))

(mf/defc change-email-form
  [{:keys [locale profile] :as props}]
  [:section.modal-content.generic-form
   [:h2 (t locale "settings.change-email-title")]

   [:& msgs/inline-banner
    {:type :info
     :content (t locale "settings.change-email-info" (:email profile))}]

   [:& form {:on-submit (partial on-submit profile)
             :spec ::email-change-form
             :validators [email-equality]
             :initial {}}
    [:& input {:type "text"
               :name :email-1
               :label (t locale "settings.new-email-label")
               :trim true}]

    [:& input {:type "text"
               :name :email-2
               :label (t locale "settings.confirm-email-label")
               :trim true}]

    [:& submit-button
     {:label (t locale "settings.change-email-submit-label")}]]])

(mf/defc change-email-modal
  {::mf/register modal/components
   ::mf/register-as :change-email}
  [props]
  (let [locale  (mf/deref i18n/locale)
        profile (mf/deref refs/profile)]
    [:div.modal-overlay
     [:div.generic-modal.change-email-modal
      [:span.close {:on-click #(modal/hide!)} i/close]
      [:& change-email-form {:locale locale :profile profile}]]]))

