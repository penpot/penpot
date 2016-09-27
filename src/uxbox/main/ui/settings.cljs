;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.settings
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.util.router :as r]
            [uxbox.util.rstore :as rs]
            [uxbox.main.ui.icons :as i]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.main.ui.settings.profile :as profile]
            [uxbox.main.ui.settings.password :as password]
            [uxbox.main.ui.settings.notifications :as notifications]
            [uxbox.main.ui.dashboard.header :refer (header)]))

(def profile-page profile/profile-page)
(def password-page password/password-page)
(def notifications-page notifications/notifications-page)
