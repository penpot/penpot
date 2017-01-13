;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require [lentes.core :as l]
            [cuerdas.core :as str]
            [potok.core :as ptk]
            [uxbox.main.store :as st]
            [uxbox.main.data.users :as udu]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.messages :refer [messages-widget]]
            [uxbox.main.ui.settings.header :refer [header]]
            [uxbox.util.forms :as forms]
            [uxbox.util.dom :as dom]
            [uxbox.util.mixins :as mx :include-macros true]))


(def form-data (forms/focus-data :profile-password st/state))
(def form-errors (forms/focus-errors :profile-password st/state))
(def set-value! (partial forms/set-value! st/store :profile-password))
(def set-errors! (partial forms/set-errors! st/store :profile-password))

(def +password-form+
  [[:password-1 forms/required forms/string [forms/min-len 6]]
   [:password-2 forms/required forms/string
    [forms/identical-to :password-1 :message "errors.form.password-not-match"]]
   [:old-password forms/required forms/string]])

(mx/defc password-form
  {:mixins [mx/reactive mx/static]}
  []
  (let [data (mx/react form-data)
        errors (mx/react form-errors)
        valid? (forms/valid? data +password-form+)]
    (letfn [(on-change [field event]
              (let [value (dom/event->value event)]
                (set-value! field value)))
            (on-submit [event]
              (println "on-submit" data)
              #_(st/emit! (udu/update-password form)))]
      [:form.password-form
       [:span.user-settings-label "Change password"]
       [:input.input-text
        {:type "password"
         :class (forms/error-class errors :old-password)
         :value (:old-password data "")
         :on-change (partial on-change :old-password)
         :placeholder "Old password"}]
       (forms/input-error errors :old-password)
       [:input.input-text
        {:type "password"
         :class (forms/error-class errors :password-1)
         :value (:password-1 data "")
         :on-change (partial on-change :password-1)
         :placeholder "New password"}]
       (forms/input-error errors :password-1)
       [:input.input-text
        {:type "password"
         :class (forms/error-class errors :password-2)
         :value (:password-2 data "")
         :on-change (partial on-change :password-2)
         :placeholder "Confirm password"}]
       (forms/input-error errors :password-2)
       [:input.btn-primary
        {:type "button"
         :class (when-not valid? "btn-disabled")
         :disabled (not valid?)
         :on-click on-submit
         :value "Update settings"}]])))

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
