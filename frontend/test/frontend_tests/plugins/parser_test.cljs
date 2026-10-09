;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.parser-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.schema :as sm]
   [app.common.types.grid :as ctg]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [app.plugins.format :as format]
   [app.plugins.parser :as parser]
   [app.plugins.strokes :as strokes]
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


(t/deftest test-parse-id-treats-blank-as-absent
  ;; `""` is truthy in ClojureScript, so an unguarded blank id reaches
  ;; `uuid/parse`. Plugins pass ids straight from JS, where an absent
  ;; value is routinely an empty string.
  (t/testing "nil is absent"
    (t/is (nil? (parser/parse-id nil))))

  (t/testing "an empty string is absent"
    (t/is (nil? (parser/parse-id ""))))

  (t/testing "a whitespace-only string is absent"
    (t/is (nil? (parser/parse-id "   "))))

  (t/testing "a valid uuid string is parsed"
    (let [id (uuid/next)]
      (t/is (= id (parser/parse-id (str id))))))

  (t/testing "a malformed id raises, so plugin bugs stay visible"
    (t/is (thrown? js/Error (parser/parse-id "not-a-uuid")))))

(t/deftest test-parse-stroke-maps-per-side-widths
  (let [stroke (parser/parse-stroke
                #js {:strokeColor "#000000"
                     :strokeWidth 1
                     :strokeWidthTop 2
                     :strokeWidthRight 3
                     :strokeWidthBottom 4
                     :strokeWidthLeft 5})]
    (t/is (= 2 (:stroke-width-top stroke)))
    (t/is (= 3 (:stroke-width-right stroke)))
    (t/is (= 4 (:stroke-width-bottom stroke)))
    (t/is (= 5 (:stroke-width-left stroke))))

  (t/testing "a uniform literal initializes all sides in simple mode"
    (let [stroke (parser/parse-stroke #js {:strokeColor "#000000" :strokeWidth 1})]
      (t/is (= "simple" (aget (format/format-stroke stroke) "strokeWidthType")))
      (t/is (= 1 (:stroke-width-top stroke)))
      (t/is (= 1 (:stroke-width-left stroke))))))

(t/deftest test-parse-stroke-applies-uniform-width-before-sides
  (let [stroke (parser/parse-stroke
                #js {:strokeColor "#000000"
                     :strokeWidthLeft 8
                     :strokeWidthBottom 8
                     :strokeWidthRight 8
                     :strokeWidthTop 8
                     :strokeWidth 1})]
    (t/is (= "simple" (aget (format/format-stroke stroke) "strokeWidthType")))
    (t/is (= 8 (:stroke-width stroke)))
    (t/is (= [8 8 8 8]
             (mapv stroke [:stroke-width-top :stroke-width-right :stroke-width-bottom :stroke-width-left])))))

(t/deftest test-parse-stroke-top-preserves-other-widths
  (let [stroke (parser/parse-stroke #js {:strokeColor "#000000" :strokeWidth 5 :strokeWidthTop 9})]
    (t/is (= [9 5 5 5]
             (mapv stroke [:stroke-width-top :stroke-width-right :stroke-width-bottom :stroke-width-left])))))

(t/deftest test-stroke-proxy-mode-transitions
  (let [^js stroke (strokes/stroke-proxy {:stroke-color "#000000" :stroke-width 1} (fn [_] nil))]
    (t/is (= "simple" (.-strokeWidthType stroke)))
    (set! (.-strokeWidthTop stroke) 8)
    (t/is (= "multiple" (.-strokeWidthType stroke)))
    (t/is (= 1 (.-strokeWidthRight stroke)))
    (set! (.-strokeWidthRight stroke) 8)
    (set! (.-strokeWidthBottom stroke) 8)
    (set! (.-strokeWidthLeft stroke) 8)
    (t/is (= "simple" (.-strokeWidthType stroke)))
    (t/is (= 8 (.-strokeWidth stroke)))
    (set! (.-strokeColor stroke) "#ff0000")
    (t/is (not (contains? (parser/parse-stroke stroke) :stroke-width-type)))
    (set! (.-strokeWidth stroke) 3)
    (t/is (= "simple" (.-strokeWidthType stroke)))
    (t/is (= [3 3 3 3]
             (mapv #(aget stroke %) ["strokeWidthTop" "strokeWidthRight" "strokeWidthBottom" "strokeWidthLeft"])))
    (set! (.-strokeWidthRight stroke) 9)
    (t/is (= "multiple" (.-strokeWidthType stroke)))
    (set! (.-strokeWidth stroke) 3)
    (t/is (= [3 3 3 3]
             (mapv #(aget stroke %) ["strokeWidthTop" "strokeWidthRight" "strokeWidthBottom" "strokeWidthLeft"])))
    (t/is (= "simple" (.-strokeWidthType stroke)))))

(t/deftest test-stroke-mode-round-trips-through-format
  (doseq [right [8 12]]
    (let [stroke (parser/parse-stroke
                  #js {:strokeColor "#000000" :strokeWidth 8 :strokeWidthRight right})]
      (t/is (= stroke (parser/parse-stroke (format/format-stroke stroke)))))))
