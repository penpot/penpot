;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.auth
  (:require [uxbox.main.ui.auth.login :as login]
            [uxbox.main.ui.auth.register :as register]
            #_[uxbox.main.ui.auth.recovery-request :as recovery-request]
            #_[uxbox.main.ui.auth.recovery :as recovery]))

(def login-page login/login-page)
(def register-page register/register-page)
;; (def recovery-page recovery/recovery-page)
;; (def recovery-request-page recovery-request/recovery-request-page)
