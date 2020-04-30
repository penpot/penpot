;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.config
  (:require [uxbox.util.object :as obj]))

(defn- get-current-origin
  []
  (let [location (obj/get goog.global "location")]
    (obj/get location "origin")))

(let [config (obj/get goog.global "uxboxConfig")
      public-url (obj/get config "publicURL" "http://localhost:6060")]

  (def default-language "en")
  (def demo-warning (obj/get config "demoWarning" true))
  (def url public-url)
  (def default-theme "default"))
