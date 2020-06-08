;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.settings.password
  (:require
   [rumext.alpha :as mf]
   [cljs.spec.alpha :as s]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.ui.components.forms :refer [input submit-button form]]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [t tr]]))

(defn- on-error
  [form error]
  (case (:code error)
    :uxbox.services.mutations.profile/old-password-not-match
    (swap! form assoc-in [:errors :password-old]
           {:message (tr "errors.wrong-old-password")})

    :else
    (let [msg (tr "generic.error")]
      (st/emit! (dm/error msg)))))

(defn- on-success
  [form]
  (let [msg (tr "settings.notifications.password-saved")]
    (st/emit! (dm/info msg))))

(defn- on-submit
  [form event]
  (dom/prevent-default event)
  (let [params (with-meta (:clean-data form)
                 {:on-success (partial on-success form)
                  :on-error (partial on-error form)})]
    (st/emit! (udu/update-password params))))

(s/def ::password-1 ::fm/not-empty-string)
(s/def ::password-2 ::fm/not-empty-string)
(s/def ::password-old ::fm/not-empty-string)

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
  [:& form {:class "password-form"
            :on-submit on-submit
            :spec ::password-form
            :validators [password-equality]
            :initial {}}
   [:h2 (t locale "settings.password-change-title")]

   [:& input
    {:type "password"
     :name :password-old
     :label (t locale "settings.old-password-label")}]

   [:& input
    {:type "password"
     :name :password-1
     :label (t locale "settings.new-password-label")}]

   [:& input
    {:type "password"
     :name :password-2
     :label (t locale "settings.confirm-password-label")}]

   [:& submit-button
    {:label (t locale "settings.profile-submit-label")}]])

;; --- Password Page

(mf/defc password-page
  [props]
  (let [locale (mf/deref i18n/locale)]
    [:section.settings-password.generic-form
     [:div.forms-container
      [:& password-form {:locale locale}]]]))
