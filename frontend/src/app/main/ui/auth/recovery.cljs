;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.recovery
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as du]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::password-1 ::us/not-empty-string)
(s/def ::password-2 ::us/not-empty-string)
(s/def ::token ::us/not-empty-string)

(s/def ::recovery-form
  (s/keys :req-un [::password-1
                   ::password-2]))

(defn- password-equality
  [errors data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]
    (cond-> errors
      (and password-1 password-2
           (not= password-1 password-2))
      (assoc :password-2 {:message "errors.password-invalid-confirmation"})

      (and password-1 (> 8 (count password-1)))
      (assoc :password-1 {:message "errors.password-too-short"}))))

(defn- on-error
  [_form _error]
  (st/emit! (dm/error (tr "auth.notifications.invalid-token-error"))))

(defn- on-success
  [_]
  (st/emit! (dm/info (tr "auth.notifications.password-changed-successfully"))
            (rt/nav :auth-login)))

(defn- on-submit
  [form _event]
  (let [mdata  {:on-error on-error
                :on-success on-success}
        params {:token (get-in @form [:clean-data :token])
                :password (get-in @form [:clean-data :password-2])}]
    (st/emit! (du/recover-profile (with-meta params mdata)))))

(mf/defc recovery-form
  [{:keys [params] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        form (fm/use-form :spec ::recovery-form
                          :validators [password-equality
                                       (fm/validate-not-empty :password-1 (tr "auth.password-not-empty"))
                                       (fm/validate-not-empty :password-2 (tr "auth.password-not-empty"))]
                          :initial params)]
    (if new-css-system
      [:& fm/form {:on-submit on-submit :form form}
       [:div {:class (stl/css :fields-row)}
        [:& fm/input {:type "password"
                      :name :password-1
                      :label (tr "auth.new-password")
                      :class (stl/css :form-field)}]]

       [:div {:class (stl/css :fields-row)}
        [:& fm/input {:type "password"
                      :name :password-2
                      :label (tr "auth.confirm-password")
                      :class (stl/css :form-field)}]]

       [:> fm/submit-button*
        {:label (tr "auth.recovery-submit")
         :class (stl/css :submit-btn)}]]

      ;; OLD
      [:& fm/form {:on-submit on-submit
                   :form form}
       [:div.fields-row
        [:& fm/input {:type "password"
                      :name :password-1
                      :label (tr "auth.new-password")}]]

       [:div.fields-row
        [:& fm/input {:type "password"
                      :name :password-2
                      :label (tr "auth.confirm-password")}]]

       [:> fm/submit-button*
        {:label (tr "auth.recovery-submit")}]])))

;; --- Recovery Request Page

(mf/defc recovery-page
  [{:keys [params] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (if new-css-system
      [:div {:class (stl/css :auth-form)}
       [:h1 {:class (stl/css :auth-title)} "Forgot your password?"]
       [:div {:class (stl/css :auth-subtitle)} "Please enter your new password"]
       [:hr {:class (stl/css :separator)}]
       [:& recovery-form {:params params}]

       [:div {:class (stl/css :links)}
        [:div {:class (stl/css :link-entry)}
         [:a {:on-click #(st/emit! (rt/nav :auth-login))}
          (tr "profile.recovery.go-to-login")]]]]

      ;; TODO
      [:section.generic-form
       [:div.form-container
        [:h1 "Forgot your password?"]
        [:div.subtitle "Please enter your new password"]
        [:& recovery-form {:params params}]

        [:div.links
         [:div.link-entry
          [:a {:on-click #(st/emit! (rt/nav :auth-login))}
           (tr "profile.recovery.go-to-login")]]]]])))
