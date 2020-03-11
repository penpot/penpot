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

(let [config (gobj/get goog.global "uxboxConfig")]
  (def default-language "en")
  (def url (gobj/get config "apiUrl" "http://localhost:6060/"))
  (def demo-warning (gobj/get config "demoWarning" true)))
