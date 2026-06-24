;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.text-test
  (:require
   [app.plugins.text :as plugins.text]
   [cljs.test :as t :include-macros true]))

;; Regression coverage for issue #9780.
;;
;; `letterSpacing` accepts negative tracking in the product UI (-200..200,
;; see typography.cljs), but the plugin validator regex rejected any leading
;; minus, so negative values were refused. `letter-spacing-re` is the shared
;; predicate behind both the shape- and range-level setters; pin its
;; accept/reject contract here.

(def ^:private letter-spacing-re @#'plugins.text/letter-spacing-re)

(defn- valid? [s] (boolean (re-matches letter-spacing-re s)))

(t/deftest letter-spacing-re-accepts-negative-values
  (t/is (valid? "-0.56"))
  (t/is (valid? "-12"))
  (t/is (valid? "-200")))

(t/deftest letter-spacing-re-accepts-non-negative-values
  (t/is (valid? "0"))
  (t/is (valid? "12"))
  (t/is (valid? "1.5")))

(t/deftest letter-spacing-re-rejects-non-numeric
  (t/is (not (valid? "abc")))
  (t/is (not (valid? "1-2")))
  (t/is (not (valid? "--1"))))
