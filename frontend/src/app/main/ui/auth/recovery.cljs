;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.auth.recovery
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i]
   [app.common.spec :as us]
   [app.main.data.auth :as uda]
   [app.main.data.messages :as dm]
   [app.main.store :as st]
   [app.main.ui.components.forms :refer [input submit-button form]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.router :as rt]))

(s/def ::password-1 ::fm/not-empty-string)
(s/def ::password-2 ::fm/not-empty-string)
(s/def ::token ::fm/not-empty-string)

(s/def ::recovery-form
  (s/keys :req-un [::password-1
                   ::password-2]))

(defn- password-equality
  [data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]
    (cond-> {}
      (and password-1 password-2
           (not= password-1 password-2))
      (assoc :password-2 {:message "errors.password-invalid-confirmation"})

      (and password-1 (> 8 (count password-1)))
      (assoc :password-1 {:message "errors.password-too-short"}))))

(defn- on-error
  [form error]
  (st/emit! (dm/error (tr "auth.notifications.invalid-token-error"))))

(defn- on-success
  [_]
  (st/emit! (dm/info (tr "auth.notifications.password-changed-succesfully"))
            (rt/nav :auth-login)))

(defn- on-submit
  [form event]
  (let [params (with-meta {:token (get-in form [:clean-data :token])
                           :password (get-in form [:clean-data :password-2])}
                 {:on-error (partial on-error form)
                  :on-success (partial on-success form)})]
    (st/emit! (uda/recover-profile params))))

(mf/defc recovery-form
  [{:keys [locale params] :as props}]
  [:& form {:on-submit on-submit
            :spec ::recovery-form
            :validators [password-equality]
            :initial params}

   [:& input {:type "password"
              :name :password-1
              :label (t locale "auth.new-password-label")}]

   [:& input {:type "password"
              :name :password-2
              :label (t locale "auth.confirm-password-label")}]

   [:& submit-button
    {:label (t locale "auth.recovery-submit-label")}]])

;; --- Recovery Request Page

(mf/defc recovery-page
  [{:keys [locale params] :as props}]
  [:section.generic-form
   [:div.form-container
    [:h1 "Forgot your password?"]
    [:div.subtitle "Please enter your new password"]

    [:& recovery-form {:locale locale :params params}]

    [:div.links
     [:div.link-entry
      [:a {:on-click #(st/emit! (rt/nav :auth-login))}
       (t locale "profile.recovery.go-to-login")]]]]])

