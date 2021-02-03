;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth.recovery-request
  (:require
   [app.common.spec :as us]
   [app.main.data.auth :as uda]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form (s/keys :req-un [::email]))

(mf/defc recovery-form
  []
  (let [form (fm/use-form :spec ::recovery-request-form
                          :initial {})

        submitted (mf/use-state false)

        on-error
        (mf/use-callback
         (fn [{:keys [code] :as error}]
           (reset! submitted false)
           (if (= code :profile-not-verified)
             (rx/of (dm/error (tr "auth.notifications.profile-not-verified")
                              {:timeout nil}))

             (rx/throw error))))

        on-success
        (mf/use-callback
         (fn []
           (reset! submitted false)
           (st/emit! (dm/info (tr "auth.notifications.recovery-token-sent"))
                     (rt/nav :auth-login))))

        on-submit
        (mf/use-callback
         (fn []
           (reset! submitted true)
           (->> (with-meta (:clean-data @form)
                  {:on-success on-success
                   :on-error on-error})
                (uda/request-profile-recovery)
                (st/emit!))))]

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
       (tr "auth.go-back-to-login")]]]]])
