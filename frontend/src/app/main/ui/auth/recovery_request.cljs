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
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.common.spec :as us]
   [app.main.data.auth :as uda]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.ui.components.forms :refer [input submit-button form]]
   [app.main.ui.icons :as i]
   [app.main.ui.navigation :as nav]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr t]]
   [app.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::recovery-request-form (s/keys :req-un [::email]))

(defn- on-submit
  [form event]
  (let [on-success #(st/emit!
                     (dm/info (tr "auth.notifications.recovery-token-sent"))
                     (rt/nav :auth-login))
        params     (with-meta (:clean-data form)
                     {:on-success on-success})]
    (st/emit! (uda/request-profile-recovery params))))

(mf/defc recovery-form
  [{:keys [locale] :as props}]
  [:& form {:on-submit on-submit
            :spec ::recovery-request-form
            :initial {}}

   [:& input {:name :email
              :label (t locale "auth.email-label")
              :help-icon i/at
              :type "text"}]

   [:& submit-button
    {:label (t locale "auth.recovery-request-submit-label")}]])

;; --- Recovery Request Page

(mf/defc recovery-request-page
  [{:keys [locale] :as props}]
  [:section.generic-form
   [:div.form-container
    [:h1 (t locale "auth.recovery-request-title")]
    [:div.subtitle (t locale "auth.recovery-request-subtitle")]

    [:& recovery-form {:locale locale}]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-login))}
       (t locale "auth.go-back-to-login")]]]]])
