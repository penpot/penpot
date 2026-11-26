;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.helpers.i18n
  (:require
   [app.util.i18n :as i18n]
   [clojure.string :as str]))

;; Both arities, like `tr`: a call site compiled for one of them looks up
;; that arity on the replacement.
(defn tr-stub
  "Stands in for `tr` in tests about the values in a message. The test run
  loads no translations, so `tr` returns the bare key and drops the values;
  this echoes the key with them."
  ([key] key)
  ([key & args]
   (str key "(" (str/join ", " (map #(if (i18n/c? %) @% %) args)) ")")))
