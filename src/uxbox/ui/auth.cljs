;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.ui.auth
  (:require [uxbox.ui.auth.login :as login]
            [uxbox.ui.auth.register :as register]))

(def login-page login/login-page)
(def register-page register/register-page)
