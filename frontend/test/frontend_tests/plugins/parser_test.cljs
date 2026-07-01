;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.parser-test
  (:require
   [app.common.geom.point :as gpt]
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

(t/deftest test-parse-overlay-action-defaults-manual-position
  (let [destination #js {"$id" (random-uuid)}
        action      (parser/parse-action
                     #js {:type "open-overlay"
                          :destination destination
                          :position "center"})]
    (t/is (= :open-overlay (:action-type action)))
    (t/is (= :center (:overlay-pos-type action)))
    (t/is (gpt/point? (:overlay-position action)))
    (t/is (= 0 (:x (:overlay-position action))))
    (t/is (= 0 (:y (:overlay-position action))))))

(t/deftest test-parse-frame-guide-calls-guide-parser
  (let [column (parser/parse-frame-guide
                #js {:type "column"
                     :display true
                     :params #js {:type "stretch"
                                  :size 12}})
        row    (parser/parse-frame-guide
                #js {:type "row"
                     :display false
                     :params #js {:type "center"
                                  :margin 4}})]
    (t/is (= :column (:type column)))
    (t/is (= true (:display column)))
    (t/is (= :stretch (get-in column [:params :type])))
    (t/is (= :row (:type row)))
    (t/is (= false (:display row)))
    (t/is (= :center (get-in row [:params :type])))))
