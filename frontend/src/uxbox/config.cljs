;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.config
  (:require [goog.object :as gobj]))

(defn- get-current-origin
  []
  (let [location (gobj/get goog.global "location")]
    (gobj/get location "origin")))

(let [config (gobj/get goog.global "uxboxConfig")
      public-url (gobj/get config "publicURL" "http://localhost:6060")]

  (def default-lang "en")
  (def demo-warning (gobj/get config "demoWarning" true))
  (def url public-url)
  (def default-theme "light"))
