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
        purl    (obj/get config "publicURL" "http://localhost:3449")
        burl    (obj/get config "backendURL" "http://localhost:6060")
        warn   (obj/get config "demoWarning" true)]
    (def default-language "en")
    (def demo-warning warn)
    (def url burl)
    (def backend-url burl)
    (def public-url purl)
    (def default-theme "default")))
