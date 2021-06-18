;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.auth.recovery-request
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form (s/keys :req-un [::email]))

(mf/defc recovery-form
  []
  (let [form      (fm/use-form :spec ::recovery-request-form :initial {})
        submitted (mf/use-state false)

        on-success
        (mf/use-callback
         (fn [_ _]
           (reset! submitted false)
           (st/emit! (dm/info (tr "auth.notifications.recovery-token-sent"))
                     (rt/nav :auth-login))))

        on-error
        (mf/use-callback
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
        (mf/use-callback
         (fn []
           (reset! submitted true)
           (let [cdata  (:clean-data @form)
                 params (with-meta cdata
                          {:on-success #(on-success cdata %)
                           :on-error #(on-error cdata %)})]
             (st/emit! (du/request-profile-recovery params)))))]

    [:& fm/form {:on-submit on-submit
                 :form form}
     [:div.fields-row
      [:& fm/input {:name :email
                    :label (tr "auth.email")
                    :help-icon i/at
                    :type "text"}]]

     [:& fm/submit-button
      {:label (tr "auth.recovery-request-submit")}]]))


;; --- Recovery Request Page

(mf/defc recovery-request-page
  []
  [:section.generic-form
   [:div.form-container
    [:h1 (tr "auth.recovery-request-title")]
    [:div.subtitle (tr "auth.recovery-request-subtitle")]
    [:& recovery-form]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-login))}
       (tr "labels.go-back")]]]]])
