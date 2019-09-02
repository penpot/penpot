;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2019 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require
   [rumext.alpha :as mf]
   [struct.alpha :as s]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as um]))

(defn- on-submit
  [event form]
  (letfn [(on-error [error]
            (case (:code error)
              :uxbox.services.users/old-password-not-match
              (swap! form assoc-in [:errors :password-old]
                     {:type ::api :message "settings.password.wrong-old-password"})

              :else (throw (ex-info "unexpected" {:error error}))))

          (on-success [_]
            (st/emit! (um/info (tr "settings.password.password-saved"))))]

    (dom/prevent-default event)
    (let [data (:clean-data form)
          opts {:on-success on-success
                :on-error on-error}]
      (st/emit! (udu/update-password data opts)))))

(s/defs ::password-form
  (s/dict :password-1 (s/&& ::s/string ::fm/not-empty-string)
          :password-2 (s/&& ::s/string ::fm/not-empty-string)
          :password-old (s/&& ::s/string ::fm/not-empty-string)))

(mf/defc password-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form ::password-form {})]
    [:form.password-form {:on-submit #(on-submit % form)}
     [:span.user-settings-label (tr "settings.password.change-password")]
     [:input.input-text
      {:type "password"
       :name "password-old"
       :value (:password-old data "")
       :class (fm/error-class form :password-old)
       :on-blur (fm/on-input-blur form :password-old)
       :on-change (fm/on-input-change form :password-old)
       :placeholder (tr "settings.password.old-password")}]
     [:& fm/field-error {:form  form :field :password-old}]

     [:input.input-text
      {:type "password"
       :name "password-1"
       :value (:password-1 data "")
       :class (fm/error-class form :password-1)
       :on-blur (fm/on-input-blur form :password-1)
       :on-change (fm/on-input-change form :password-1)
       :placeholder (tr "settings.password.new-password")}]
     [:& fm/field-error {:form form :field :password-1}]

     [:input.input-text
      {:type "password"
       :name "password-2"
       :value (:password-2 data "")
       :class (fm/error-class form :password-2)
       :on-blur (fm/on-input-blur form :password-2)
       :on-change (fm/on-input-change form :password-2)
       :placeholder (tr "settings.password.confirm-password")}]
     [:& fm/field-error {:form form :field :password-2}]

     [:input.btn-primary
      {:type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))
       :value (tr "settings.update-settings")}]]))

;; --- Password Page

(mf/defc password-page
  [props]
  [:section.dashboard-content.user-settings
   [:section.user-settings-content
    [:& password-form]]])
