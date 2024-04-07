;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.recovery-request
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.spec :as us]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.v2.core :as rx]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form (s/keys :req-un [::email]))
(defn handle-error-messages
  [errors _data]
  (d/update-when errors :email
                 (fn [{:keys [code] :as error}]
                   (cond-> error
                     (= code :missing)
                     (assoc :message (tr "errors.email-invalid"))))))

(mf/defc recovery-form
  [{:keys [on-success-callback] :as props}]
  (let [form      (fm/use-form :spec ::recovery-request-form
                               :validators [handle-error-messages]
                               :initial {})
        submitted (mf/use-state false)

        default-success-finish #(st/emit! (msg/info (tr "auth.notifications.recovery-token-sent")))

        on-success
        (mf/use-callback
         (fn [cdata _]
           (reset! submitted false)
           (if (nil? on-success-callback)
             (default-success-finish)
             (on-success-callback (:email cdata)))))

        on-error
        (mf/use-callback
         (fn [data cause]
           (reset! submitted false)
           (let [code (-> cause ex-data :code)]
             (case code
               :profile-not-verified
               (rx/of (msg/error (tr "auth.notifications.profile-not-verified")))

               :profile-is-muted
               (rx/of (msg/error (tr "errors.profile-is-muted")))

               :email-has-permanent-bounces
               (rx/of (msg/error (tr "errors.email-has-permanent-bounces" (:email data))))

               (rx/throw cause)))))

        on-submit
        (mf/use-callback
         (fn []
           (reset! submitted true)
           (let [cdata  (:clean-data @form)
                 params (with-meta cdata
                          {:on-success #(on-success cdata %)
                           :on-error #(on-error cdata %)})]
             (reset! form nil)
             (st/emit! (du/request-profile-recovery params)))))]

    [:& fm/form {:on-submit on-submit
                 :class (stl/css :recovery-request-form)
                 :form form}
     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:name :email
                    :label (tr "auth.email")
                    :type "text"
                    :class (stl/css :form-field)}]]

     [:> fm/submit-button*
      {:label (tr "auth.recovery-request-submit")
       :data-test "recovery-resquest-submit"
       :class (stl/css :recover-btn)}]]))


;; --- Recovery Request Page

(mf/defc recovery-request-page
  [{:keys [params on-success-callback go-back-callback] :as props}]
  (let [default-go-back #(st/emit! (rt/nav :auth-login))
        go-back (or go-back-callback default-go-back)]
    [:div {:class (stl/css :auth-form-wrapper)}
     [:h1 {:class (stl/css :auth-title)} (tr "auth.recovery-request-title")]
     [:div {:class (stl/css :auth-subtitle)} (tr "auth.recovery-request-subtitle")]
     [:hr {:class (stl/css :separator)}]

     [:& recovery-form {:params params :on-success-callback on-success-callback}]
     [:hr {:class (stl/css :separator)}]
     [:div {:class (stl/css :go-back)}
      [:& lk/link {:action go-back
                   :class (stl/css :go-back-link)
                   :data-test "go-back-link"}
       (tr "labels.go-back")]]]))
