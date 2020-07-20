;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.change-email
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.main.data.auth :as da]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.data.users :as du]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.forms :refer [input submit-button form]]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.ui.messages :as msgs]
   [uxbox.main.ui.modal :as modal]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(s/def ::email-1 ::fm/email)
(s/def ::email-2 ::fm/email)

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
    (= (:code error) :uxbox.services.mutations.profile/email-already-exists)
    (swap! form (fn [data]
                  (let [error {:message (tr "errors.email-already-exists")}]
                    (assoc-in data [:errors :email-1] error))))

    :else
    (let [msg (tr "errors.unexpected-error")]
      (st/emit! (dm/error msg)))))

(defn- on-submit
  [form event]
  (let [data (with-meta {:email (get-in form [:clean-data :email-1])}
               {:on-error (partial on-error form)})]
    (st/emit! (du/request-email-change data))))

(mf/defc change-email-form
  [{:keys [locale profile] :as props}]
  [:section.modal-content.generic-form
   [:h2 (t locale "settings.change-email-title")]

   [:& msgs/inline-banner
    {:type :info
     :content (t locale "settings.change-email-info" (:email profile))}]

   [:& form {:on-submit on-submit
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

(mf/defc change-email-confirmation
  [{:keys [locale profile] :as locale}]
  [:section.modal-content.generic-form.confirmation
   [:h2 (t locale "settings.verification-sent-title")]


   [:& msgs/inline-banner
    {:type :info
     :content (t locale "settings.change-email-info2" (:email profile))}]

   [:button.btn-primary.btn-large
    {:on-click #(modal/hide!)}
    (t locale "settings.close-modal-label")]])

(mf/defc change-email-modal
  [props]
  (let [locale (mf/deref i18n/locale)
        profile (mf/deref refs/profile)]
    [:section.generic-modal.change-email-modal
     [:span.close {:on-click #(modal/hide!)} i/close]
     (if (:pending-email profile)
       [:& change-email-confirmation {:locale locale :profile profile}]
       [:& change-email-form {:locale locale :profile profile}])]))
