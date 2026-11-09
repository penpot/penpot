;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.referenced-token-value-test
  (:require
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [cljs.test :as t :include-macros true]))

(def ^:private typography-value
  {:font-family ["Inter"]
   :font-size "16"
   :font-weight "600"})

(def ^:private shadow-value
  [{:offset-x "4" :offset-y "4" :blur "8" :spread "0" :color "#000000" :inset false}
   {:offset-x "0" :offset-y "1" :blur "2" :spread "0" :color "#00000033" :inset true}])

(def ^:private tokens
  {"base"         {:name "base" :type :typography :value typography-value}
   "alias"        {:name "alias" :type :typography :value "{base}"}
   "loop-a"       {:name "loop-a" :type :typography :value "{loop-b}"}
   "loop-b"       {:name "loop-b" :type :typography :value "{loop-a}"}
   "font-size"    {:name "font-size" :type :font-size :value "16"}
   "shadow-base"  {:name "shadow-base" :type :shadow :value shadow-value}
   "shadow-alias" {:name "shadow-alias" :type :shadow :value "{shadow-base}"}})

(t/deftest referenced-typography-value
  (t/testing "returns the value of the referenced typography token"
    (t/is (= typography-value (csu/referenced-token-value tokens "{base}" :typography)))
    (t/is (= typography-value (csu/referenced-token-value tokens " {base} " :typography))))

  (t/testing "follows chained references"
    (t/is (= typography-value (csu/referenced-token-value tokens "{alias}" :typography))))

  (t/testing "returns nil when the reference cannot be detached"
    (t/is (nil? (csu/referenced-token-value tokens "{missing}" :typography)))
    (t/is (nil? (csu/referenced-token-value tokens "{font-size}" :typography)))
    (t/is (nil? (csu/referenced-token-value tokens "{loop-a}" :typography)))
    (t/is (nil? (csu/referenced-token-value tokens "{base} {alias}" :typography)))
    (t/is (nil? (csu/referenced-token-value tokens "base" :typography)))
    (t/is (nil? (csu/referenced-token-value tokens nil :typography)))))

(t/deftest referenced-shadow-value
  (t/testing "returns the value of the referenced shadow token"
    (t/is (= shadow-value (csu/referenced-token-value tokens "{shadow-base}" :shadow))))

  (t/testing "follows chained references"
    (t/is (= shadow-value (csu/referenced-token-value tokens "{shadow-alias}" :shadow))))

  (t/testing "returns nil for a token of another type"
    (t/is (nil? (csu/referenced-token-value tokens "{base}" :shadow)))
    (t/is (nil? (csu/referenced-token-value tokens "{shadow-base}" :typography)))))
