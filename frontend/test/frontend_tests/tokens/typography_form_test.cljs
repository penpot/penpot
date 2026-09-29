;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.typography-form-test
  (:require
   [app.main.ui.workspace.tokens.management.forms.typography :as typography]
   [cljs.test :as t :include-macros true]))

(def ^:private base-value
  {:font-family ["Inter"]
   :font-size "16"
   :font-weight "600"})

(def ^:private tokens
  {"base"      {:name "base" :type :typography :value base-value}
   "alias"     {:name "alias" :type :typography :value "{base}"}
   "loop-a"    {:name "loop-a" :type :typography :value "{loop-b}"}
   "loop-b"    {:name "loop-b" :type :typography :value "{loop-a}"}
   "font-size" {:name "font-size" :type :font-size :value "16"}})

(t/deftest referenced-typography-value
  (t/testing "returns the value of the referenced typography token"
    (t/is (= base-value (typography/referenced-typography-value tokens "{base}")))
    (t/is (= base-value (typography/referenced-typography-value tokens " {base} "))))

  (t/testing "follows chained references"
    (t/is (= base-value (typography/referenced-typography-value tokens "{alias}"))))

  (t/testing "returns nil when the reference cannot be detached"
    (t/is (nil? (typography/referenced-typography-value tokens "{missing}")))
    (t/is (nil? (typography/referenced-typography-value tokens "{font-size}")))
    (t/is (nil? (typography/referenced-typography-value tokens "{loop-a}")))
    (t/is (nil? (typography/referenced-typography-value tokens "{base} {alias}")))
    (t/is (nil? (typography/referenced-typography-value tokens "base")))
    (t/is (nil? (typography/referenced-typography-value tokens nil)))))
