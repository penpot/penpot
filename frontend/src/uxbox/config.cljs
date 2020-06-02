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
        puri   (obj/get config "publicURI" "http://localhost:3449")
        buri   (obj/get config "backendURI" "http://localhost:3449")
        gcid   (obj/get config "googleClientID" true)
        warn   (obj/get config "demoWarning" true)]
    (def default-language "en")
    (def demo-warning warn)
    (def backend-uri buri)
    (def google-client-id gcid)
    (def public-uri puri)
    (def default-theme "default")))
