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
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.main.refs :as refs]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :as i18n :refer [tr t]]))

(s/def ::fullname ::fm/not-empty-string)
(s/def ::lang (s/nilable ::fm/not-empty-string))
(s/def ::theme ::fm/not-empty-string)
(s/def ::email ::fm/email)

(s/def ::profile-form
  (s/keys :req-un [::fullname ::lang ::theme ::email]))

(defn- on-error
  [error form]
  (case (:code error)
    :uxbox.services.users/email-already-exists
    (swap! form assoc-in [:errors :email]
           {:type ::api
            :message "errors.api.form.email-already-exists"})

    :uxbox.services.users/username-already-exists
    (swap! form assoc-in [:errors :username]
           {:type ::api
            :message "errors.api.form.username-already-exists"})))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [data (:clean-data form)
        on-success #(st/emit! (dm/info (tr "settings.profile.profile-saved")))
        on-error #(on-error % form)]
    (st/emit! (udu/update-profile (with-meta data
                                    {:on-success on-success
                                     :on-error on-error})))))

;; --- Profile Form

(mf/defc profile-form
  [props]
  (let [locale (i18n/use-locale)
        form (fm/use-form ::profile-form #(deref refs/profile))
        data (:data form)]
    (prn "data" form)
    [:form.profile-form {:on-submit #(on-submit % form)}
     [:span.settings-label (t locale "settings.profile.section-basic-data")]

     [:input.input-text
      {:type "text"
       :name "fullname"
       :class (fm/error-class form :fullname)
       :on-blur (fm/on-input-blur form :fullname)
       :on-change (fm/on-input-change form :fullname)
       :value (:fullname data "")
       :placeholder (t locale "settings.profile.your-name")}]
     [:& fm/field-error {:form form
                         :type #{::api}
                         :field :fullname}]

     [:input.input-text
      {:type "email"
       :name "email"
       :class (fm/error-class form :email)
       :on-blur (fm/on-input-blur form :email)
       :on-change (fm/on-input-change form :email)
       :value (:email data "")
       :placeholder (t locale "settings.profile.your-email")}]
     [:& fm/field-error {:form form
                         :type #{::api}
                         :field :email}]

     [:span.settings-label (t locale "settings.profile.lang")]
     [:select.input-select {:value (:lang data)
                            :name "lang"
                            :class (fm/error-class form :lang)
                            :on-blur (fm/on-input-blur form :lang)
                            :on-change (fm/on-input-change form :lang)}
      [:option {:value "en"} "English"]
      [:option {:value "fr"} "Français"]]

     [:span.user-settings-label (tr "settings.profile.section-theme-data")]
     [:select.input-select {:value (:theme data)
                            :name "theme"
                            :class (fm/error-class form :theme)
                            :on-blur (fm/on-input-blur form :theme)
                            :on-change (fm/on-input-change form :theme)}
      [:option {:value "light"} "Light"]
      [:option {:value "dark"} "Dark"]]

     [:input.btn-primary
      {:type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))
       :value (t locale "settings.update-settings")}]]))

;; --- Profile Photo Form

(mf/defc profile-photo-form
  [props]
  (let [profile (mf/deref refs/profile)
        photo (:photo-uri profile)
        photo (if (or (str/empty? photo) (nil? photo))
                "images/avatar.jpg"
                photo)

        on-change
        (fn [event]
          (let [target (dom/get-target event)
                file (-> (dom/get-files target)
                         (array-seq)
                         (first))]
            (st/emit! (udu/update-photo {:file file}))
            (dom/clean-value! target)))]
    [:form.avatar-form
     [:img {:src photo}]
     [:input {:type "file"
              :value ""
              :on-change on-change}]]))

;; --- Profile Page

(mf/defc profile-page
  {::mf/wrap-props false}
  [props]
  (let [locale (i18n/use-locale)]
    [:section.settings-profile
     [:span.settings-label (t locale "settings.profile.your-avatar")]
     [:& profile-photo-form]
     [:& profile-form]]))
