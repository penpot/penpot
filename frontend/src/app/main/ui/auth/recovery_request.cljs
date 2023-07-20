;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.recovery-request
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form
  (s/keys :req-un [::email]))

(defn handle-error-messages
  [errors _data]
  (d/update-when errors :email
                 (fn [{:keys [code] :as error}]
                   (cond-> error
                     (= code :missing)
                     (assoc :message (tr "errors.email-invalid"))))))

(mf/defc recovery-form
  {::mf/wrap-props false}
  [{:keys [on-success]}]
  (let [form      (fm/use-form :spec ::recovery-request-form
                               :validators [handle-error-messages]
                               :initial {})
        submitted (mf/use-state false)

        default-on-success
        (mf/use-fn #(st/emit! (dm/info (tr "auth.notifications.recovery-token-sent"))))

        on-success
        (mf/use-fn
         (mf/deps default-on-success on-success)
         (fn [cdata _]
           (reset! submitted false)
           (if (fn? on-success)
             (on-success (:email cdata))
             (default-on-success))))

        on-error
        (mf/use-fn
         (fn [data {:keys [code] :as error}]
           (reset! submitted false)
           (case code
             :profile-not-verified
             (rx/of (dm/error (tr "auth.notifications.profile-not-verified") {:timeout nil}))

             :profile-is-muted
             (rx/of (dm/error (tr "errors.profile-is-muted")))

             :email-has-permanent-bounces
             (rx/of (dm/error (tr "errors.email-has-permanent-bounces" (:email data))))

             (rx/throw error))))

        on-submit
        (mf/use-fn
         (fn []
           (reset! submitted true)
           (let [cdata  (:clean-data @form)
                 params (with-meta cdata
                          {:on-success #(on-success cdata %)
                           :on-error #(on-error cdata %)})]
             (reset! form nil)
             (st/emit! (du/request-profile-recovery params)))))]

    [:& fm/form {:on-submit on-submit :form form}
     [:div.fields-row
      [:& fm/input
       {:name :email
        :label (tr "auth.email")
        :help-icon i/at
        :type "text"}]]

     [:> fm/submit-button*
      {:label (tr "auth.recovery-request-submit")
       :data-test "recovery-resquest-submit"}]]))

;; --- Recovery Request Page

(mf/defc recovery-request-page
  {::mf/wrap-props false}
  [{:keys [params on-success on-go-back]}]
  (let [default-go-back (mf/use-fn #(st/emit! (rt/nav :auth-login)))
        on-go-back      (or on-go-back default-go-back)]
    [:section.generic-form
     [:div.form-container
      [:h1 (tr "auth.recovery-request-title")]
      [:div.subtitle (tr "auth.recovery-request-subtitle")]
      [:& recovery-form
       {:params params
        :on-success on-success}]
      [:div.links
       [:div.link-entry
        [:& lk/link {:on-click on-go-back
                     :data-test "go-back-link"}
         (tr "labels.go-back")]]]]]))
