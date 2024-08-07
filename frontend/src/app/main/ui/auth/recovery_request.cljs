;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.recovery-request
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.messages :as msg]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(def ^:private schema:recovery-request-form
  [:map {:title "RecoverRequestForm"}
   [:email ::sm/email]])

(mf/defc recovery-form
  [{:keys [on-success-callback] :as props}]
  (let [form      (fm/use-form :schema schema:recovery-request-form
                               :initial {})
        submitted (mf/use-state false)

        default-success-finish
        (mf/use-fn
         #(st/emit! (msg/info (tr "auth.notifications.recovery-token-sent"))))

        on-success
        (mf/use-fn
         (fn [cdata _]
           (reset! submitted false)
           (if (nil? on-success-callback)
             (default-success-finish)
             (on-success-callback (:email cdata)))))

        on-error
        (mf/use-fn
         (fn [data cause]
           (reset! submitted false)
           (let [code (-> cause ex-data :code)]
             (case code
               :profile-not-verified
               (rx/of (msg/error (tr "auth.notifications.profile-not-verified")))

               :profile-is-muted
               (rx/of (msg/error (tr "errors.profile-is-muted")))

               (:email-has-permanent-bounces
                :email-has-complaints)
               (rx/of (msg/error (tr "errors.email-has-permanent-bounces" (:email data))))

               (rx/throw cause)))))

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

    [:& fm/form {:on-submit on-submit
                 :class (stl/css :recovery-request-form)
                 :form form}
     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:name :email
                    :label (tr "auth.work-email")
                    :type "text"
                    :class (stl/css :form-field)}]]

     [:> fm/submit-button*
      {:label (tr "auth.recovery-request-submit")
       :data-testid "recovery-resquest-submit"
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
                   :data-testid "go-back-link"}
       (tr "labels.go-back")]]]))
