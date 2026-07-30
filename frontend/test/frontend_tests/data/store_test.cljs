;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.store-test
  "Unit tests for app.main.store.
  Tests cover:
    - format-last-events          – empty, single, multi, column alignment"
  (:require
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

(t/deftest format-last-events-empty
  (t/testing "empty events produce empty string"
    (t/is (= "" (st/format-last-events [])))))

(t/deftest format-last-events-single
  (t/testing "a single event shows (+0ms) and its name"
    (let [result (st/format-last-events [{:name ":test/event" :t (js/Date. 1000)}])]
      (t/is (str/includes? result "(+0ms)"))
      (t/is (str/includes? result ":test/event"))
      (t/is (= 1 (count (str/split result "\n")))))))

(t/deftest format-last-events-multi
  (t/testing "multiple events show correct deltas and event names"
    (let [events [{:name ":event/a" :t (js/Date. 0)}
                  {:name ":event/b" :t (js/Date. 500)}
                  {:name ":event/c" :t (js/Date. 2500)}]
          lines  (str/split (st/format-last-events events) "\n")]
      (t/is (= 3 (count lines)))
      (t/is (some #(str/includes? % ":event/a") lines))
      (t/is (some #(str/includes? % ":event/b") lines))
      (t/is (some #(str/includes? % ":event/c") lines))
      (t/is (str/includes? (nth lines 0) "(+0ms)"))
      (t/is (str/includes? (nth lines 1) "(+500ms)"))
      (t/is (str/includes? (nth lines 2) "(+2000ms)")))))

(t/deftest format-last-events-alignment
  (t/testing "event names start at the same column across all lines"
    (let [events [{:name ":evt-a" :t (js/Date. 0)}
                  {:name ":evt-b" :t (js/Date. 500)}]
          lines  (str/split (st/format-last-events events) "\n")
          col-a  (.indexOf (nth lines 0) ":evt-a")
          col-b  (.indexOf (nth lines 1) ":evt-b")]
      (t/is (pos? col-a))
      (t/is (= col-a col-b)))))
