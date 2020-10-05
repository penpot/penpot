;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.settings.password
  (:require
   [app.common.spec :as us]
   [app.main.data.messages :as dm]
   [app.main.data.users :as udu]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [cljs.spec.alpha :as s]
   [rumext.alpha :as mf]))

(defn- on-error
  [form error]
  (case (:code error)
    :app.services.mutations.profile/old-password-not-match
    (swap! form assoc-in [:errors :password-old]
           {:message (tr "errors.wrong-old-password")})

    :else
    (let [msg (tr "generic.error")]
      (st/emit! (dm/error msg)))))

(defn- on-success
  [form]
  (let [msg (tr "dashboard.notifications.password-saved")]
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
(s/def ::password-old ::us/not-empty-string)

(defn- password-equality
  [data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]

    (cond-> {}
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
  (let [form (fm/use-form :spec ::password-form
                          :validators [password-equality]
                          :initial {})]
    [:& fm/form {:class "password-form"
                 :on-submit on-submit
                 :form form}
     [:h2 (t locale "dashboard.settings.password-change-title")]
     [:div.fields-row
      [:& fm/input
       {:type "password"
        :name :password-old
        :label (t locale "dashboard.settings.old-password-label")}]]

     [:div.fields-row
      [:& fm/input
       {:type "password"
        :name :password-1
        :label (t locale "dashboard.settings.new-password-label")}]]

     [:div.fields-row
      [:& fm/input
       {:type "password"
        :name :password-2
        :label (t locale "dashboard.settings.confirm-password-label")}]]

     [:& fm/submit-button
      {:label (t locale "dashboard.settings.profile-submit-label")}]]))

;; --- Password Page

(mf/defc password-page
  [{:keys [locale]}]
  [:section.dashboard-settings.form-container
   [:div.form-container
    [:& password-form {:locale locale}]]])
