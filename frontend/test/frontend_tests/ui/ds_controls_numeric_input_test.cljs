;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.ui.ds-controls-numeric-input-test
  (:require
   [app.main.ui.ds.controls.numeric-input :refer [next-focus-index]]
   [cljs.test :as t :include-macros true]))

(def ^:private sample-options
  [{:id "a" :type :item :name "Alpha"}
   {:id "b" :type :group :name "Group"}
   {:id "c" :type :item :name "Charlie"}
   {:id "d" :type :separator :name "---"}
   {:id "e" :type :item :name "Echo"}])

(t/deftest test-next-focus-index
  (t/testing "returns index of next focusable item going down"
    (t/is (= 2 (next-focus-index sample-options "a" :down))))

  (t/testing "returns index of next focusable item going up"
    (t/is (= 0 (next-focus-index sample-options "c" :up))))

  (t/testing "wraps around going down"
    (t/is (= 0 (next-focus-index sample-options "e" :down))))

  (t/testing "wraps around going up"
    (t/is (= 4 (next-focus-index sample-options "a" :up))))

  (t/testing "works when options is a delay"
    (let [delayed-options (delay sample-options)]
      (t/is (= 2 (next-focus-index delayed-options "a" :down)))))

  (t/testing "works with nil focused-id (no current selection)"
    (t/is (= 0 (next-focus-index sample-options nil :down)))))
