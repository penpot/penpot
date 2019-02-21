;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings.notifications
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.util.router :as r]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.settings.header :refer (header)]))

(mx/defc notifications-page
  {:mixins [mx/static]}
  [own]
  [:main.dashboard-main
   (header)
   [:section.dashboard-content.user-settings
    [:section.user-settings-content
     [:span.user-settings-label (tr "settings.notifications.none")]
     [:p (tr "settings.notifications.description")]
     [:div.input-radio.radio-primary
      [:input {:type "radio"
               :id "notification-1"
               :name "notification"
               :value "none"}]
      [:label {:for "notification-1"
               :value (tr "settings.notifications.none")} (tr "settings.notifications.none")]
      [:input {:type "radio"
               :id "notification-2"
               :name "notification"
               :value "every-hour"}]
      [:label {:for "notification-2"
               :value (tr "settings.notifications.every-hour")} (tr "settings.notifications.every-hour")]
      [:input {:type "radio"
               :id "notification-3"
               :name "notification"
               :value "every-day"}]
      [:label {:for "notification-3"
               :value (tr "settings.notifications.every-day")} (tr "settings.notifications.every-day")]]
     [:input.btn-primary {:type "submit"
                          :class "btn-disabled"
                          :disabled true
                          :value (tr "settings.update-settings")}]
     ]]])
