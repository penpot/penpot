;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require
   [rumext.alpha :as mf]
   [cljs.spec.alpha :as s]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]))

(defn- on-error
  [form error]
  (case (:code error)
    :uxbox.services.users/old-password-not-match
    (swap! form assoc-in [:errors :password-old]
           {:type ::api :message "settings.password.wrong-old-password"})

    :else (throw (ex-info "unexpected" {:error error}))))

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (let [data (:clean-data form)
        mdata {:on-success #(st/emit! (dm/info (tr "settings.password.password-saved")))
              :on-error #(on-error form %)}]
    (st/emit! (udu/update-password (with-meta data mdata)))))

(s/def ::password-1 ::fm/not-empty-string)
(s/def ::password-2 ::fm/not-empty-string)
(s/def ::password-old ::fm/not-empty-string)

(defn password-equality
  [data]
  (let [password-1 (:password-1 data)
        password-2 (:password-2 data)]
    (when (and password-1 password-2
               (not= password-1 password-2))
      {:password-2 {:code ::password-not-equal
                    :message "profile.password.not-equal"}})))

(s/def ::password-form
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(mf/defc password-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form2 :spec ::password-form
                                              :validators [password-equality]
                                              :initial {})]
    [:form.password-form {:on-submit #(on-submit % form)}
     [:span.settings-label (tr "settings.password.change-password")]
     [:input.input-text
      {:type "password"
       :name "password-old"
       :value (:password-old data "")
       :class (fm/error-class form :password-old)
       :on-blur (fm/on-input-blur form :password-old)
       :on-change (fm/on-input-change form :password-old)
       :placeholder (tr "settings.password.old-password")}]

     [:& fm/field-error {:form  form :field :password-old :type ::api}]

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

     [:input.btn-primary.btn-large
      {:type "submit"
       :class (when-not (:valid form) "btn-disabled")
       :disabled (not (:valid form))
       :value (tr "settings.update-settings")}]]))

;; --- Password Page

(mf/defc password-page
  [props]
  [:section.settings-password
   [:& password-form]])
