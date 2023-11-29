;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.settings.password
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as udu]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(defn- on-error
  [form error]
  (case (:code error)
    :old-password-not-match
    (swap! form assoc-in [:errors :password-old]
           {:message (tr "errors.wrong-old-password")})
    :email-as-password
    (swap! form assoc-in [:errors :password-1]
           {:message (tr "errors.email-as-password")})

    (let [msg (tr "generic.error")]
      (st/emit! (dm/error msg)))))

(defn- on-success
  [form]
  (reset! form nil)
  (let [password-old-node (dom/get-element "password-old")
        msg (tr "dashboard.notifications.password-saved")]
    (dom/clean-value! password-old-node)
    (dom/focus! password-old-node)
    (st/emit! (dm/success msg))))

(defn- on-submit
  [form event]
  (dom/prevent-default event)
  (let [params (with-meta (:clean-data @form)
                 {:on-success (partial on-success form)
                  :on-error (partial on-error form)})]
    (st/emit! (udu/update-password params))))

(s/def ::password-1 ::us/not-empty-string)
(s/def ::password-2 ::us/not-empty-string)
(s/def ::password-old (s/nilable ::us/string))

(defn- password-equality
  [errors data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]

    (cond-> errors
      (and password-1 password-2 (not= password-1 password-2))
      (assoc :password-2 {:message (tr "errors.password-invalid-confirmation")})

      (and password-1 (> 8 (count password-1)))
      (assoc :password-1 {:message (tr "errors.password-too-short")}))))

(s/def ::password-form
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(mf/defc password-form
  [{:keys [locale] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        initial (mf/use-memo (constantly {:password-old nil}))
        form (fm/use-form :spec ::password-form
                          :validators [(fm/validate-not-all-spaces :password-old (tr "auth.password-not-empty"))
                                       (fm/validate-not-empty :password-1 (tr "auth.password-not-empty"))
                                       (fm/validate-not-empty :password-2 (tr "auth.password-not-empty"))
                                       password-equality]
                          :initial initial)]
    (if new-css-system
      [:& fm/form {:class (stl/css :password-form)
                   :on-submit on-submit
                   :form form}

       [:div {:class (stl/css :fields-row)}
        [:& fm/input
         {:type "password"
          :name :password-old
          :auto-focus? true
          :label (t locale "labels.old-password")}]]

       [:div {:class (stl/css :fields-row)}
        [:& fm/input
         {:type "password"
          :name :password-1
          :label (t locale "labels.new-password")}]]

       [:div {:class (stl/css :fields-row)}
        [:& fm/input
         {:type "password"
          :name :password-2
          :label (t locale "labels.confirm-password")}]]

       [:> fm/submit-button*
        {:label (t locale "dashboard.update-settings")
         :data-test "submit-password"
         :class (stl/css :update-btn)}]]

      ;; OLD
      [:& fm/form {:class "password-form"
                   :on-submit on-submit
                   :form form}
       [:h2 (t locale "dashboard.password-change")]
       [:div.fields-row
        [:& fm/input
         {:type "password"
          :name :password-old
          :auto-focus? true
          :label (t locale "labels.old-password")}]]

       [:div.fields-row
        [:& fm/input
         {:type "password"
          :name :password-1
          :label (t locale "labels.new-password")}]]

       [:div.fields-row
        [:& fm/input
         {:type "password"
          :name :password-2
          :label (t locale "labels.confirm-password")}]]

       [:> fm/submit-button*
        {:label (t locale "dashboard.update-settings")
         :data-test "submit-password"}]])))

;; --- Password Page

(mf/defc password-page
  [{:keys [locale]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    (mf/use-effect
     #(dom/set-html-title (tr "title.settings.password")))

    (if new-css-system
      [:section {:class (stl/css :dashboard-settings)}
       [:div {:class (stl/css :form-container)}
        [:h2 (t locale "dashboard.password-change")]
        [:& password-form {:locale locale}]]]

      ;; old
      [:section.dashboard-settings.form-container
       [:div.form-container
        [:& password-form {:locale locale}]]])))
