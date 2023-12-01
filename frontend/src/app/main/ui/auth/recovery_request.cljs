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
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.link :as lk]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
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
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        form      (fm/use-form :spec ::recovery-request-form
                               :validators [handle-error-messages]
                               :initial {})
        submitted (mf/use-state false)

        default-success-finish #(st/emit! (dm/info (tr "auth.notifications.recovery-token-sent")))

        on-success
        (mf/use-callback
         (fn [cdata _]
           (reset! submitted false)
           (if (nil? on-success-callback)
             (default-success-finish)
             (on-success-callback (:email cdata)))))

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
             (reset! form nil)
             (st/emit! (du/request-profile-recovery params)))))]

    (if new-css-system
      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div {:class (stl/css :fields-row)}
        [:& fm/input {:name :email
                      :label (tr "auth.email")
                      :type "text"
                      :class (stl/css :form-field)}]]

       [:> fm/submit-button*
        {:label (tr "auth.recovery-request-submit")
         :data-test "recovery-resquest-submit"
         :class (stl/css :recover-btn)}]]

      ;; OLD
      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.fields-row
        [:& fm/input {:name :email
                      :label (tr "auth.email")
                      :help-icon i/at
                      :type "text"}]]

       [:> fm/submit-button*
        {:label (tr "auth.recovery-request-submit")
         :data-test "recovery-resquest-submit"}]])))


;; --- Recovery Request Page

(mf/defc recovery-request-page
  [{:keys [params on-success-callback go-back-callback] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        default-go-back #(st/emit! (rt/nav :auth-login))
        go-back (or go-back-callback default-go-back)]
    (if new-css-system
      [:div {:class (stl/css :auth-form)}
       [:h1 {:class (stl/css :auth-title)} (tr "auth.recovery-request-title")]
       [:div {:class (stl/css :auth-subtitle)} (tr "auth.recovery-request-subtitle")]
       [:hr {:class (stl/css :separator)}]

       [:& recovery-form {:params params :on-success-callback on-success-callback}]

       [:div {:class (stl/css :link-entry)}
        [:& lk/link {:action go-back
                     :data-test "go-back-link"}
         (tr "labels.go-back")]]]

      ;; old
      [:section.generic-form
       [:div.form-container
        [:h1 (tr "auth.recovery-request-title")]
        [:div.subtitle (tr "auth.recovery-request-subtitle")]
        [:& recovery-form {:params params :on-success-callback on-success-callback}]
        [:div.links
         [:div.link-entry
          [:& lk/link {:action go-back
                       :data-test "go-back-link"}
           (tr "labels.go-back")]]]]])))
