;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.data-macros-test
  (:require
   [app.common.data.macros :as dm]
   [clojure.test :as t]))

(defn- eager?
  "Mirrors the build mode check performed by `dm/lazy-delay`."
  []
  #?(:clj  true
     :cljs (not dm/lazy-delay?)))

(t/deftest lazy-delay-returns-a-delay-with-the-value
  (let [value (dm/lazy-delay (str "foo" "bar"))]
    (t/is (delay? value))
    (t/is (= "foobar" @value))))

(t/deftest lazy-delay-evaluates-immediately-by-default
  (let [calls (atom 0)
        _     (dm/lazy-delay (swap! calls inc))]
    (t/is (= (if (eager?) 1 0) @calls))))

(t/deftest lazy-delay-evaluates-the-expression-only-once
  (let [calls (atom 0)
        value (dm/lazy-delay (swap! calls inc))]
    (t/is (= 1 @value))
    (t/is (= 1 @value))
    (t/is (= 1 @calls))))
