;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.profile
  (:require
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr]]
   [uxbox.util.interop :refer [iterable->seq]]))


(def form-data (fm/focus-data :profile st/state))
(def form-errors (fm/focus-errors :profile st/state))

(def assoc-value (partial fm/assoc-value :profile))
(def assoc-error (partial fm/assoc-error :profile))
(def clear-form (partial fm/clear-form :profile))

(def profile-ref
  (-> (l/key :profile)
      (l/derive st/state)))

(s/def ::fullname ::fm/non-empty-string)
(s/def ::username ::fm/non-empty-string)
(s/def ::email ::fm/email)

(s/def ::profile-form
  (s/keys :req-un [::fullname
                   ::username
                   ::email]))

(defn- on-error
  [{:keys [code] :as payload}]
  (case code
    :uxbox.services.users/registration-disabled
    (st/emit! (tr "errors.api.form.registration-disabled"))
    :uxbox.services.users/email-already-exists
    (st/emit! (assoc-error :email (tr "errors.api.form.email-already-exists")))
    :uxbox.services.users/username-already-exists
    (st/emit! (assoc-error :username (tr "errors.api.form.username-already-exists")))))

(defn- on-field-change
  [event field]
  (let [value (dom/event->value event)]
    (st/emit! (assoc-value field value))))

;; --- Profile Form
(mf/def profile-form
  :mixins [mf/memo mf/reactive mf/sync-render (fm/clear-mixin st/store :profile)]
  :render
  (fn [own props]
    (let [data (merge {:theme "light"}
                      (mf/react profile-ref)
                      (mf/react form-data))
          errors (mf/react form-errors)
          valid? (fm/valid? ::profile-form data)
          theme (:theme data)
          on-success #(st/emit! (clear-form))
          on-submit #(st/emit! (udu/update-profile data on-success on-error))
          on-lang-change (fn [event]
                           (let [lang (read-string (dom/event->value event))]
                             (prn "on-lang-change" lang)
                             (i18n/set-current-locale! lang)))]
      [:form.profile-form
       [:span.user-settings-label (tr "settings.profile.section-basic-data")]
       [:input.input-text
        {:type "text"
         :on-change #(on-field-change % :fullname)
         :value (:fullname data "")
         :placeholder (tr "settings.profile.your-name")}]
       [:input.input-text
        {:type "text"
         :on-change #(on-field-change % :username)
         :value (:username data "")
         :placeholder (tr "settings.profile.your-username")}]
       (fm/input-error errors :username)
       [:input.input-text
        {:type "email"
         :on-change #(on-field-change % :email)
         :value (:email data "")
         :placeholder (tr "settings.profile.your-email")}]
       (fm/input-error errors :email)

       [:span.user-settings-label (tr "settings.profile.section-i18n-data")]
       [:select.input-select {:value (pr-str (mf/deref i18n/locale))
                              :on-change on-lang-change}
        [:option {:value ":en"} "English"]
        [:option {:value ":fr"} "Français"]]

       [:input.btn-primary
        {:type "button"
         :class (when-not valid? "btn-disabled")
         :disabled (not valid?)
         :on-click on-submit
         :value (tr "settings.update-settings")}]])))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  {:wrap [mf/reactive*]}
  []
  (letfn [(on-change [event]
            (let [target (dom/get-target event)
                  file (-> (dom/get-files target)
                           (iterable->seq)
                           (first))]
              (st/emit! (udu/update-photo file))
              (dom/clean-value! target)))]
    (let [{:keys [photo]} (mf/react profile-ref)
          photo (if (or (str/empty? photo) (nil? photo))
                  "images/avatar.jpg"
                  photo)]
      [:form.avatar-form
       [:img {:src photo}]
       [:input {:type "file"
                :value ""
                :on-change on-change}]])))

;; --- Profile Page

(mf/defc profile-page
  []
  [:section.dashboard-content.user-settings
   [:section.user-settings-content
    [:span.user-settings-label (tr "settings.profile.your-avatar")]
    [:& profile-photo-form]
    (profile-form)]])
