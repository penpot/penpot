;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.plugins.parser-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.grid :as ctg]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.plugins.parser :as parser]
   [cljs.test :as t :include-macros true]))

(defn- overlay-action
  [{:keys [type destination position manual-position-location]}]
  (let [action (js-obj "type" type
                       "destination" (js-obj "$id" destination))]
    (when (some? position)
      (unchecked-set action "position" position))
    (when (some? manual-position-location)
      (unchecked-set action "manualPositionLocation" manual-position-location))
    action))

(defn- parse-overlay-interaction
  [action]
  (parser/parse-interaction "click" (overlay-action action) nil))

(defn- valid-interaction?
  [interaction]
  (sm/validate ctsi/schema:interaction interaction))

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

(t/deftest test-parse-frame-guides
  ;; Regression test for issue #9773.
  ;;
  ;; `parse-frame-guide` returned the parser fns for column/row instead of
  ;; calling them with the guide, and the `board.guides` setter validated
  ;; against an unregistered `::ctg/grid` reference (now `ctg/schema:grid`).
  ;; Parsed guides must be plain maps that validate against the same direct
  ;; schema the setter uses, and clearing (empty input) must validate too.
  (let [column #js {:type "column" :display true
                    :params #js {:color #js {:color "#DE4762" :opacity 0.2}
                                 :type "stretch" :size 12 :gutter 16 :margin 16}}
        square #js {:type "square" :display true
                    :params #js {:color #js {:color "#DE4762" :opacity 0.2} :size 8}}
        parsed (parser/parse-frame-guides #js [column square])]
    (t/is (= :column (-> parsed first :type)))
    (t/is (= :square (-> parsed second :type)))
    (t/is (map? (-> parsed first :params)))
    (t/is (sm/validate [:vector ctg/schema:grid] parsed)))

  (t/testing "clearing guides with an empty vector validates"
    (t/is (sm/validate [:vector ctg/schema:grid] (parser/parse-frame-guides #js [])))))

(t/deftest test-parse-overlay-action-position-is-optional
  (t/testing "open-overlay defaults omitted position to center"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination})]
      (t/is (= :open-overlay (:action-type result)))
      (t/is (= :click (:event-type result)))
      (t/is (= destination (:destination result)))
      (t/is (= :center (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result))))

  (t/testing "toggle-overlay preserves manualPositionLocation"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction
                       {:type "toggle-overlay"
                        :destination destination
                        :position "manual"
                        :manual-position-location #js {:x 10 :y 20}})
          position    (:overlay-position result)]
      (t/is (= :toggle-overlay (:action-type result)))
      (t/is (= :manual (:overlay-pos-type result)))
      (t/is (gpt/point? position))
      (t/is (= 10 (:x position)))
      (t/is (= 20 (:y position)))
      (t/is (valid-interaction? result))))

  (t/testing "explicit center position does not require manualPositionLocation"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination
                                                  :position "center"})]
      (t/is (= :center (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result))))

  (t/testing "manual position without manualPositionLocation still parses"
    (let [destination (uuid/next)
          result      (parse-overlay-interaction {:type "open-overlay"
                                                  :destination destination
                                                  :position "manual"})]
      (t/is (= :manual (:overlay-pos-type result)))
      (t/is (not (contains? result :overlay-position)))
      (t/is (valid-interaction? result)))))

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

