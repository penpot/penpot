;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [struct.core :as stt]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.users :as udu]
   [uxbox.main.store :as st]
   [uxbox.util.dom :as dom]
   [uxbox.util.forms :as fm]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.messages :as um]))

(stt/defs password-form-spec
  {:password-1 [stt/required stt/string]
   :password-2 [stt/required stt/string]
   :password-old [stt/required stt/string]})

(defn- on-submit
  [event form]
  (dom/prevent-default event)
  (prn "on-submit" form)
  #_(let [data (:clean-data form)
        opts {:on-success #(prn "On Success" %)
              :on-error #(on-error % form)}]
    (st/emit! (udu/update-profile data opts))))




  ;; #_(let [data (mx/deref form-data)
  ;;       errors (mx/react form-errors)
  ;;       valid? (fm/valid? ::password-form data)]
  ;;   (letfn [(on-change [field event]
  ;;             (let [value (dom/event->value event)]
  ;;               (st/emit! (assoc-value field value))))
  ;;           (on-success []
  ;;             (st/emit! (um/info (tr "settings.password.password-saved"))))
  ;;           (on-error [{:keys [code] :as payload}]
  ;;             (case code
  ;;               :uxbox.services.users/old-password-not-match
  ;;               (st/emit! (assoc-error :password-old (tr "settings.password.wrong-old-password")))

  ;;               :else
  ;;               (throw (ex-info "unexpected" {:error payload}))))
  ;;           (on-submit [event]
  ;;             (st/emit! (udu/update-password data
  ;;                                            :on-success on-success
  ;;                                            :on-error on-error)))]



(mf/defc password-form
  [props]
  (let [{:keys [data] :as form} (fm/use-form {:initial {} :spec password-form-spec})]
    (prn "password-form" form)
    [:form.password-form {:on-submit #(on-submit % form)}
     [:span.user-settings-label (tr "settings.password.change-password")]
     [:input.input-text
      {:type "password"
       :name "password-old"
       :class (fm/error-class form :password-old)
       :value (:password-old data "")
       :on-blur (fm/on-input-blur form)
       :on-change (fm/on-input-change form)
       :placeholder (tr "settings.password.old-password")}]
     [:& fm/error-input {:form  form :field :password-old}]

     [:input.input-text
      {:type "password"
       :name "password-1"
       :class (fm/error-class form :password-1)
       :value (:password-1 data "")
       :on-blur (fm/on-input-blur form)
       :on-change (fm/on-input-change form)
       :placeholder (tr "settings.password.new-password")}]
     [:& fm/error-input {:form form :field :password-1}]
     [:input.input-text
      {:type "password"
       :name "password-2"
       :class (fm/error-class form :password-2)
       :value (:password-2 data "")
       :on-blur (fm/on-input-blur form)
       :on-change (fm/on-input-change form)
       :placeholder (tr "settings.password.confirm-password")}]
     [:& fm/error-input {:form  form :field :password-2}]
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
