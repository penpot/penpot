;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.auth.login
  (:require
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]
   [uxbox.common.spec :as us]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.auth :as da]
   [uxbox.main.store :as st]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.ui.components.forms :refer [input submit-button form]]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr t]]
   [uxbox.util.router :as rt]))

(s/def ::email ::us/email)
(s/def ::password ::us/not-empty-string)

(s/def ::login-form
  (s/keys :req-un [::email ::password]))

(defn- on-error
  [form error]
  (st/emit! (dm/error (tr "errors.auth.unauthorized"))))

(defn- on-submit
  [form event]
  (let [params (with-meta (:clean-data form)
                 {:on-error (partial on-error form)})]
    (st/emit! (da/login params))))

(mf/defc login-form
  [{:keys [locale] :as props}]
  [:& form {:on-submit on-submit
            :spec ::login-form
            :initial {}}
   [:& input
    {:name :email
     :type "text"
     :tab-index "2"
     :help-icon i/at
     :label (t locale "auth.email-label")}]
   [:& input
    {:type "password"
     :name :password
     :tab-index "3"
     :help-icon i/eye
     :label (t locale "auth.password-label")}]
   [:& submit-button
    {:label (t locale "auth.login-submit-label")}]])

(mf/defc login-page
  [{:keys [locale] :as props}]
  [:div.generic-form.login-form
   [:div.form-container
    [:h1 (t locale "auth.login-title")]
    [:div.subtitle (t locale "auth.login-subtitle")]

    [:& login-form {:locale locale}]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-recovery-request))
           :tab-index "5"}
       (t locale "auth.forgot-password")]]

     [:div.link-entry
      [:span (t locale "auth.register-label") " "]
      [:a {:on-click #(st/emit! (rt/nav :auth-register))
           :tab-index "6"}
       (t locale "auth.register")]]

     [:div.link-entry
      [:span (t locale "auth.create-demo-profile-label") " "]
      [:a {:on-click #(st/emit! da/create-demo-profile)
           :tab-index "6"}
       (t locale "auth.create-demo-profile")]]]]])
