;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.metrics-test
  (:require
   [app.metrics :as mtx]
   [clojure.test :as t]))

(t/deftest label-coercion
  (t/are [value fallback expected]
         (= expected (mtx/label value fallback))
    "fs"      "unknown" "fs"
    :s3        "unknown" "s3"
    200       "unknown" "200"
    nil       "unknown" "unknown"
    nil       "default" "default"))
