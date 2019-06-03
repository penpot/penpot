;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require [cljs.spec.alpha :as s :include-macros true]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.users :as udu]
            [uxbox.builtins.icons :as i]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.settings.header :refer [header]]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.forms :as fm]
            [uxbox.util.dom :as dom]
            [uxbox.util.messages :as um]
            [rumext.core :as mx :include-macros true]))

(def form-data (fm/focus-data :profile-password st/state))
(def form-errors (fm/focus-errors :profile-password st/state))

(def assoc-value (partial fm/assoc-value :profile-password))
(def assoc-error (partial fm/assoc-error :profile-password))
(def clear-form (partial fm/clear-form :profile-password))

;; TODO: add better password validation

(s/def ::password-1 ::fm/non-empty-string)
(s/def ::password-2 ::fm/non-empty-string)
(s/def ::password-old ::fm/non-empty-string)

(s/def ::password-form
  (s/keys :req-un [::password-1
                   ::password-2
                   ::password-old]))

(mx/defc password-form
  {:mixins [mx/reactive mx/static]}
  []
  (let [data (mx/react form-data)
        errors (mx/react form-errors)
        valid? (fm/valid? ::password-form data)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (st/emit! (assoc-value field value))))
            (on-success []
              (st/emit! (um/info (tr "settings.password.password-saved"))))
            (on-error [{:keys [code] :as payload}]
              (case code
                :uxbox.services.users/old-password-not-match
                (st/emit! (assoc-error :password-old (tr "settings.password.wrong-old-password")))

                :else
                (throw (ex-info "unexpected" {:error payload}))))
            (on-submit [event]
              (st/emit! (udu/update-password data
                                             :on-success on-success
                                             :on-error on-error)))]
      [:form.password-form
       [:span.user-settings-label (tr "settings.password.change-password")]
       [:input.input-text
        {:type "password"
         :class (fm/error-class errors :password-old)
         :value (:password-old data "")
         :on-change (partial on-change :password-old)
         :placeholder (tr "settings.password.old-password")}]
       (fm/input-error errors :password-old)
       [:input.input-text
        {:type "password"
         :class (fm/error-class errors :password-1)
         :value (:password-1 data "")
         :on-change (partial on-change :password-1)
         :placeholder (tr "settings.password.new-password")}]
       (fm/input-error errors :password-1)
       [:input.input-text
        {:type "password"
         :class (fm/error-class errors :password-2)
         :value (:password-2 data "")
         :on-change (partial on-change :password-2)
         :placeholder (tr "settings.password.confirm-password")}]
       (fm/input-error errors :password-2)
       [:input.btn-primary
        {:type "button"
         :class (when-not valid? "btn-disabled")
         :disabled (not valid?)
         :on-click on-submit
         :value (tr "settings.update-settings")}]])))

;; --- Password Page

(mx/defc password-page
  {:mixins [mx/static]}
  []
  [:main.dashboard-main
   (messages-widget)
   (header)
   [:section.dashboard-content.user-settings
    [:section.user-settings-content
     (password-form)]]])
