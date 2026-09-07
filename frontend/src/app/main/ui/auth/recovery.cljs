;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
  [form error]
  (let [{:keys [type code] :as edata} (ex-data error)]
    (if (= [:validation :weak-password] [type code])
      (let [details (:details edata)
            options (when (seq details)
                      (mapv tr details))]
        (swap! form assoc-in [:extra-errors :password-1]
               {:message (tr "errors.weak-password")
                :options options}))

      (let [msg (tr "errors.invalid-recovery-token")]
        (st/emit! (ntf/error msg))))))

(defn- on-success
  [_]
  (st/emit! (ntf/info (tr "auth.notifications.password-changed-successfully"))
            (rt/nav :auth-login)))

(defn- on-submit
  [form _event]
  (let [mdata  {:on-error (partial on-error form)
                :on-success on-success}
        params {:token (get-in @form [:clean-data :token])
                :password (get-in @form [:clean-data :password-2])}]
    (st/emit! (du/recover-profile (with-meta params mdata)))))

(mf/defc recovery-form*
  [{:keys [params]}]
  (let [form (fm/use-form :schema schema:recovery-form
                          :initial params)]

    [:& fm/form {:on-submit on-submit
                 :class (stl/css :form)
                 :form form}

     [:div {:class (stl/css :form-row)}
      [:& fm/input {:type "password"
                    :name :password-1
                    :show-success? true
                    :label (tr "auth.new-password")
                    :class (stl/css :form-field)}]]

     [:div {:class (stl/css :form-row)}
      [:& fm/input {:type "password"
                    :name :password-2
                    :show-success? true
                    :label (tr "auth.confirm-password")
                    :class (stl/css :form-field)}]]

     [:> fm/submit-button* {:label (tr "auth.recovery-submit")
                            :class (stl/css :form-submit-btn)}]]))

;; --- Recovery Request Page

(mf/defc recovery-page*
  [{:keys [params]}]
  [:div {:class (stl/css :wrapper)}
   [:h1 {:class (stl/css :title)} (tr "auth.recovery-request-title")]
   [:div {:class (stl/css :subtitle)} (tr "auth.recovery-request-subtitle")]
   [:hr {:class (stl/css :separator)}]
   [:> recovery-form* {:params params}]

   [:div {:class (stl/css :links)}
    [:div {:class (stl/css :go-back-row)}
     [:a {:on-click #(st/emit! (rt/nav :auth-login))
          :class (stl/css :go-back-link)}
      (tr "profile.recovery.go-to-login")]]]])
