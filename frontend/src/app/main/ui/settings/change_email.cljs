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
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.main.ui.messages :as msgs]
   [app.util.i18n :as i18n :refer [tr t]]
   [beicon.core :as rx]
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
    (rx/throw error)))

(defn- on-success
  [form data]
  (let [email   (get-in @form [:clean-data :email-1])
        message (tr "notifications.validation-email-sent" email)]
    (st/emit! (dm/info message)
              (modal/hide))))

(defn- on-submit
  [form event]
  (let [params {:email (get-in @form [:clean-data :email-1])}
        mdata  {:on-error (partial on-error form)
                :on-success (partial on-success form)}]
    (st/emit! (du/request-email-change (with-meta params mdata)))))

(mf/defc change-email-modal
  {::mf/register modal/components
   ::mf/register-as :change-email}
  []
  (let [locale  (mf/deref i18n/locale)
        profile (mf/deref refs/profile)
        form    (fm/use-form :spec ::email-change-form
                             :validators [email-equality]
                             :initial profile)
        on-close
        (mf/use-callback (st/emitf (modal/hide)))]

    [:div.modal-overlay
     [:div.modal-container.change-email-modal.form-container
      [:& fm/form {:form form
                   :on-submit on-submit}

       [:div.modal-header
        [:div.modal-header-title
         [:h2 (t locale "modals.change-email.title")]]
        [:div.modal-close-button
         {:on-click on-close} i/close]]

       [:div.modal-content
        [:& msgs/inline-banner
         {:type :info
          :content (t locale "modals.change-email.info" (:email profile))}]

        [:div.fields-row
         [:& fm/input {:type "text"
                       :name :email-1
                       :label (t locale "modals.change-email.new-email")
                       :trim true}]]
        [:div.fields-row
         [:& fm/input {:type "text"
                       :name :email-2
                       :label (t locale "modals.change-email.confirm-email")
                       :trim true}]]]

       [:div.modal-footer
        [:div.action-buttons
         [:& fm/submit-button
          {:label (t locale "modals.change-email.submit")}]]]]]]))



