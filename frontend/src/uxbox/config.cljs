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
  (let [config (obj/get global "uxboxConfig" {})
        wuri   (obj/get global "uxboxWorkerURI" "/js/worker.js")]
    (def default-language "en")
    (def demo-warning     (obj/get config "demoWarning" false))
    (def google-client-id (obj/get config "googleClientID"))
    (def login-with-ldap  (obj/get config "loginWithLDAP" false))
    (def worker-uri       wuri)
    (def public-uri       (or (obj/get config "publicURI")
                              (.-origin ^js js/location)))
    (def media-uri        (str public-uri "/media"))
    (def default-theme    "default")))

(defn resolve-media-path
  [path]
  (str media-uri "/" path))
