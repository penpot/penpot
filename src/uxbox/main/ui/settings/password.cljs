;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.password
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [lentes.core :as l]
            [cuerdas.core :as str]
            [uxbox.util.schema :as sc]
            [uxbox.main.state :as st]
            [uxbox.util.i18n :as t :refer (tr)]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.data.users :as udu]
            [uxbox.main.data.forms :as udf]
            [uxbox.main.ui.icons :as i]
            [uxbox.main.ui.forms :as forms]
            [uxbox.main.ui.messages :as uum]
            [uxbox.util.mixins :as mx]
            [uxbox.main.ui.settings.header :refer (header)]
            [uxbox.util.dom :as dom]))

;; --- Password Form

(def formdata
  (-> (l/in [:forms :profile/password])
      (l/derive st/state)))

(def formerrors
  (-> (l/in [:errors :profile/password])
      (l/derive st/state)))

(def assign-field-value
  (partial udf/assign-field-value :profile/password))

(defn password-form-render
  [own]
  (let [form (mx/react formdata)
        errors (mx/react formerrors)
        valid? (sc/valid? form udu/update-password-schema)]
    (letfn [(on-field-change [field event]
              (let [value (dom/event->value event)]
                (rs/emit! (assign-field-value field value))))
            (on-submit [event]
              (rs/emit! (udu/update-password form)))]
      (html
       [:form.password-form
        [:span.user-settings-label "Change password"]
        [:input.input-text
         {:type "password"
          :class (forms/error-class errors :old-password)
          :value (:old-password form "")
          :on-change (partial on-field-change :old-password)
          :placeholder "Old password"}]
        (forms/input-error errors :old-password)
        [:input.input-text
         {:type "password"
          :class (forms/error-class errors :password-1)
          :value (:password-1 form "")
          :on-change (partial on-field-change :password-1)
          :placeholder "New password"}]
        (forms/input-error errors :password-1)
        [:input.input-text
         {:type "password"
          :class (forms/error-class errors :password-2)
          :value (:password-2 form "")
          :on-change (partial on-field-change :password-2)
          :placeholder "Confirm password"}]
        (forms/input-error errors :password-2)
        [:input.btn-primary
         {:type "button"
          :class (when-not valid? "btn-disabled")
          :disabled (not valid?)
          :on-click on-submit
          :value "Update settings"}]]))))

(def password-form
  (mx/component
   {:render password-form-render
    :name "password-form"
    :mixins [mx/static (mx/local) mx/reactive]}))

;; --- Password Page

(defn password-page-render
  [own]
  (html
   [:main.dashboard-main
    (header)
    (uum/messages)
    [:section.dashboard-content.user-settings
     [:section.user-settings-content
      (password-form)]]]))

(def password-page
  (mx/component
   {:render password-page-render
    :name "password-page"
    :mixins [mx/static]}))
