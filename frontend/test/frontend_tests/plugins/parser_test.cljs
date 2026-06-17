;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.parser-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.plugins.parser :as parser]
   [cljs.test :as t :include-macros true]))

(t/deftest test-parse-point-returns-gpt-point-record
  ;; Regression test for issue #8409.
  ;;
  ;; The plugin parser used to return a plain map `{:x … :y …}`, but the
  ;; shape-interaction schema expects `::gpt/point` (a Point record).
  ;; Plugin `addInteraction` calls with an `open-overlay` action and
  ;; `manualPositionLocation` were silently rejected by validation.
  (t/testing "parse-point returns nil for nil input"
    (t/is (nil? (parser/parse-point nil))))

  (t/testing "parse-point returns a gpt/point record for valid input"
    (let [result (parser/parse-point #js {:x 10 :y 20})]
      (t/is (gpt/point? result))
      (t/is (= 10 (:x result)))
      (t/is (= 20 (:y result)))))

  (t/testing "parse-point passes gpt/point? for a zero point"
    (let [result (parser/parse-point #js {:x 0 :y 0})]
      (t/is (gpt/point? result))
      (t/is (= 0 (:x result)))
      (t/is (= 0 (:y result))))))

(t/deftest test-parse-close-overlay-without-animation-validates
  (t/testing "close-overlay without animation parses and validates"
    (let [result (parser/parse-interaction "click" #js {:type "close-overlay"} nil)]
      (t/is (= {:event-type :click
                :action-type :close-overlay}
               result))
      (t/is (false? (contains? result :animation)))
      (t/is (true? (sm/validate ctsi/schema:interaction result)))))

  (t/testing "close-overlay preserves destination without animation"
    (let [destination-id (uuid/next)
          result         (parser/parse-interaction
                          "click"
                          #js {:type "close-overlay"
                               :destination #js {"$id" destination-id}}
                          nil)]
      (t/is (= destination-id (:destination result)))
      (t/is (false? (contains? result :animation)))
      (t/is (true? (sm/validate ctsi/schema:interaction result)))))

  (t/testing "close-overlay preserves an explicit dissolve animation"
    (let [result (parser/parse-interaction
                  "click"
                  #js {:type "close-overlay"
                       :animation #js {:type "dissolve"
                                       :duration 300
                                       :easing "linear"}}
                  nil)]
      (t/is (= {:animation-type :dissolve
                :duration 300
                :easing :linear}
               (:animation result)))
      (t/is (true? (sm/validate ctsi/schema:interaction result))))))
