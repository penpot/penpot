;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.extends
  "A dummy namespace for closure library and other global objects
  extensions"
  (:require
   [promesa.impl :as pi])
  (:import
   goog.async.Deferred))

(pi/extend-promise! Deferred)
