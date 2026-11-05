;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-util-object-test
  "The javascript-object helpers the browser calls are built with:
  reads that survive nil, writes that return the object, and the
  clj→props translation of React prop names."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.util.object :as obj]))

(t/deftest get-reads-and-survives-nil
  (let [o #js {:a 1}]
    (t/is (= 1 (obj/get o "a")))
    (t/is (nil? (obj/get o "missing")))
    (t/is (nil? (obj/get nil "a")))
    (t/is (= :dflt (obj/get o "missing" :dflt)))
    ;; the default fires on undefined only: a nil object reads nil.
    ;; Same as the legacy (probe-verified), pinned so it stays that way.
    (t/is (nil? (obj/get nil "a" :dflt)))))

(t/deftest get-in-threads-nil-safely
  (let [o #js {:a #js {:b 2}}]
    (t/is (= 2 (obj/get-in o ["a" "b"])))
    (t/is (nil? (obj/get-in o ["a" "missing"])))
    (t/is (nil? (obj/get-in nil ["a"])))
    (t/is (= :dflt (obj/get-in o ["a" "missing"] :dflt)))))

(t/deftest set-and-update-return-the-object
  (let [o (obj/new)]
    (t/is (identical? o (obj/set! o "a" 1)))
    (t/is (= 1 (obj/get o "a")))
    (t/is (identical? o (obj/update o "a" inc)))
    (t/is (= 2 (obj/get o "a")))
    ;; `update` on a missing key applies `f` to the sentinel instead of
    ;; skipping it — the legacy does exactly the same (probe-verified
    ;; `:app.util.object/not-found1`), so this pins parity, not intent:
    ;; never "fix" one side without the other.
    (t/is (identical? o (obj/update o "missing" (constantly :set))))
    (t/is (= :set (obj/get o "missing")))))

(t/deftest merge-copies-without-touching-the-inputs
  (let [a #js {:x 1}
        b #js {:y 2}
        m (obj/merge a b)]
    (t/is (= 1 (obj/get m "x")))
    (t/is (= 2 (obj/get m "y")))
    (t/is (nil? (obj/get a "y")))))

(t/deftest clj->props-camelizes-and-renames-class
  (let [props (obj/clj->props {:background-color "red" :class "a"})]
    (t/is (= "red" (obj/get props "backgroundColor")))
    (t/is (= "a" (obj/get props "className")))))

(t/deftest in-checks-ownership
  (let [o #js {:a 1}]
    (t/is (true? (obj/in? o "a")))
    (t/is (false? (obj/in? o "missing")))
    (t/is (true? (obj/contains? o "a")))))
