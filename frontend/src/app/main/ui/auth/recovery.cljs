;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.auth.recovery
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:recovery-form
  [:and
   [:map {:title "RecoveryForm"}
    [:token ::sm/text]
    [:password-1 ::sm/password]
    [:password-2 ::sm/password]]
   [:fn {:error/code "errors.password-invalid-confirmation"
         :error/field :password-2}
    (fn [{:keys [password-1 password-2]}]
      (= password-1 password-2))]])

(defn- on-error
  [_form _error]
  (st/emit! (ntf/error (tr "errors.invalid-recovery-token"))))

(defn- on-success
  [_]
  (st/emit! (ntf/info (tr "auth.notifications.password-changed-successfully"))
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
  (let [form (fm/use-form :schema schema:recovery-form
                          :initial params)]

    [:& fm/form {:on-submit on-submit
                 :class (stl/css :recovery-form)
                 :form form}

     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:type "password"
                    :name :password-1
                    :show-success? true
                    :label (tr "auth.new-password")
                    :class (stl/css :form-field)}]]

     [:div {:class (stl/css :fields-row)}
      [:& fm/input {:type "password"
                    :name :password-2
                    :show-success? true
                    :label (tr "auth.confirm-password")
                    :class (stl/css :form-field)}]]

     [:> fm/submit-button*
      {:label (tr "auth.recovery-submit")
       :class (stl/css :submit-btn)}]]))

;; --- Recovery Request Page

(mf/defc recovery-page
  [{:keys [params] :as props}]
  [:div {:class (stl/css :auth-form-wrapper)}
   [:h1 {:class (stl/css :auth-title)} "Forgot your password?"]
   [:div {:class (stl/css :auth-subtitle)} "Please enter your new password"]
   [:hr {:class (stl/css :separator)}]
   [:& recovery-form {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :go-back)}
     [:a {:on-click #(st/emit! (rt/nav :auth-login))
          :class (stl/css :go-back-link)}
      (tr "profile.recovery.go-to-login")]]]])
