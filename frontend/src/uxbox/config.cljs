;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.config
  (:require [uxbox.util.object :as obj]))

(this-as global
  (let [config (obj/get global "uxboxConfig")
        puri   (obj/get config "publicURI")
        wuri   (obj/get config "workerURI")
        gcid   (obj/get config "googleClientID" true)
        lwl    (obj/get config "loginWithLDAP" false)
        warn   (obj/get config "demoWarning" true)]
    (def default-language "en")
    (def demo-warning warn)
    (def google-client-id gcid)
    (def login-with-ldap lwl)
    (def worker-uri wuri)
    (def public-uri puri)
    (def default-theme "default")))
