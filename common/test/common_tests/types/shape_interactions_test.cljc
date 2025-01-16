;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.shape-interactions-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.shape :as cts]
   [app.common.types.shape.interactions :as ctsi]
   [app.common.uuid :as uuid]
   [clojure.pprint :refer [pprint]]
   [clojure.test :as t]))

(t/deftest set-event-type
  (let [interaction ctsi/default-interaction
        shape       (cts/setup-shape {:type :rect})
        frame       (cts/setup-shape {:type :frame})]

    (t/testing "Set event type unchanged"
      (let [new-interaction
            (ctsi/set-event-type interaction :click shape)]
        (t/is (= :click (:event-type new-interaction)))))

    (t/testing "Set event type changed"
      (let [new-interaction
            (ctsi/set-event-type interaction :mouse-press shape)]
        (t/is (= :mouse-press (:event-type new-interaction)))))

    (t/testing "Set after delay on non-frame"
      (let [result (ex/try!
                    (ctsi/set-event-type interaction :after-delay shape))]
        (t/is (ex/exception? result))))

    (t/testing "Set after delay on frame"
      (let [new-interaction
            (ctsi/set-event-type interaction :after-delay frame)]
        (t/is (= :after-delay (:event-type new-interaction)))
        (t/is (= 600 (:delay new-interaction)))))

    (t/testing "Set after delay with previous data"
      (let [interaction (assoc interaction :delay 300)
            new-interaction
            (ctsi/set-event-type interaction :after-delay frame)]
        (t/is (= :after-delay (:event-type new-interaction)))
        (t/is (= 300 (:delay new-interaction)))))))

(t/deftest set-action-type
  (let [interaction ctsi/default-interaction]

    (t/testing "Set action type unchanged"
      (let [new-interaction
            (ctsi/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))))

    (t/testing "Set action type changed"
      (let [new-interaction
            (ctsi/set-action-type interaction :prev-screen)]
        (t/is (= :prev-screen (:action-type new-interaction)))))

    (t/testing "Set action type navigate"
      (let [interaction {:event-type :click
                         :action-type :prev-screen}
            new-interaction
            (ctsi/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))
        (t/is (nil? (:destination new-interaction)))
        (t/is (= false (:preserve-scroll new-interaction)))))

    (t/testing "Set action type navigate with previous data"
      (let [destination (uuid/next)
            interaction {:event-type      :click
                         :action-type     :prev-screen
                         :destination     destination
                         :preserve-scroll true}
            new-interaction
            (ctsi/set-action-type interaction :navigate)]
        (t/is (= :navigate (:action-type new-interaction)))
        (t/is (= destination (:destination new-interaction)))
        (t/is (= true (:preserve-scroll new-interaction)))))

    (t/testing "Set action type open-overlay"
      (let [new-interaction
            (ctsi/set-action-type interaction :open-overlay)]
        (t/is (= :open-overlay (:action-type new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set action type open-overlay with previous data"
      (let [interaction (assoc interaction :overlay-pos-type :top-left
                               :overlay-position (gpt/point 100 200))
            new-interaction
            (ctsi/set-action-type interaction :open-overlay)]
        (t/is (= :open-overlay (:action-type new-interaction)))
        (t/is (= :top-left (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 100 200) (:overlay-position new-interaction)))))

    (t/testing "Set action type toggle-overlay"
      (let [new-interaction
            (ctsi/set-action-type interaction :toggle-overlay)]
        (t/is (= :toggle-overlay (:action-type new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set action type toggle-overlay with previous data"
      (let [interaction (assoc interaction :overlay-pos-type :top-left
                               :overlay-position (gpt/point 100 200))
            new-interaction
            (ctsi/set-action-type interaction :toggle-overlay)]
        (t/is (= :toggle-overlay (:action-type new-interaction)))
        (t/is (= :top-left (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 100 200) (:overlay-position new-interaction)))))

    (t/testing "Set action type close-overlay"
      (let [new-interaction
            (ctsi/set-action-type interaction :close-overlay)]
        (t/is (= :close-overlay (:action-type new-interaction)))
        (t/is (nil? (:destination new-interaction)))))

    (t/testing "Set action type close-overlay with previous data"
      (let [destination (uuid/next)
            interaction (assoc interaction :destination destination)
            new-interaction
            (ctsi/set-action-type interaction :close-overlay)]
        (t/is (= :close-overlay (:action-type new-interaction)))
        (t/is (= destination (:destination new-interaction)))))

    (t/testing "Set action type prev-screen"
      (let [new-interaction
            (ctsi/set-action-type interaction :prev-screen)]
        (t/is (= :prev-screen (:action-type new-interaction)))))

    (t/testing "Set action type open-url"
      (let [new-interaction
            (ctsi/set-action-type interaction :open-url)]
        (t/is (= :open-url (:action-type new-interaction)))
        (t/is (= "" (:url new-interaction)))))

    (t/testing "Set action type open-url with previous data"
      (let [interaction (assoc interaction :url "https://example.com")
            new-interaction
            (ctsi/set-action-type interaction :open-url)]
        (t/is (= :open-url (:action-type new-interaction)))
        (t/is (= "https://example.com" (:url new-interaction)))))))

(t/deftest option-delay
  (let [frame (cts/setup-shape {:type :frame})
        i1    ctsi/default-interaction
        i2    (ctsi/set-event-type i1 :after-delay frame)]

    (t/testing "Has delay"
      (t/is (not (ctsi/has-delay i1)))
      (t/is (ctsi/has-delay i2)))

    (t/testing "Set delay"
      (let [new-interaction (ctsi/set-delay i2 1000)]
        (t/is (= 1000 (:delay new-interaction)))))))

(t/deftest option-destination
  (let [destination (uuid/next)
        i1          ctsi/default-interaction
        i2          (ctsi/set-action-type i1 :prev-screen)
        i3          (ctsi/set-action-type i1 :open-overlay)]

    (t/testing "Has destination"
      (t/is (ctsi/has-destination i1))
      (t/is (not (ctsi/has-destination i2))))

    (t/testing "Set destination"
      (let [new-interaction (ctsi/set-destination i1 destination)]
        (t/is (= destination (:destination new-interaction)))
        (t/is (nil? (:overlay-pos-type new-interaction)))
        (t/is (nil? (:overlay-position new-interaction)))))

    (t/testing "Set destination of overlay"
      (let [new-interaction (ctsi/set-destination i3 destination)]
        (t/is (= destination (:destination new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))))


(t/deftest option-preserve-scroll
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-action-type i1 :prev-screen)]

    (t/testing "Has preserve-scroll"
      (t/is (ctsi/has-preserve-scroll i1))
      (t/is (not (ctsi/has-preserve-scroll i2))))

    (t/testing "Set preserve-scroll"
      (let [new-interaction (ctsi/set-preserve-scroll i1 true)]
        (t/is (= true (:preserve-scroll new-interaction)))))))


(t/deftest option-url
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-action-type i1 :open-url)]

    (t/testing "Has url"
      (t/is (not (ctsi/has-url i1)))
      (t/is (ctsi/has-url i2)))

    (t/testing "Set url"
      (let [new-interaction (ctsi/set-url i2 "https://example.com")]
        (t/is (= "https://example.com" (:url new-interaction)))))))


(t/deftest option-overlay-opts
  (let [base-frame    (-> (cts/setup-shape {:type :frame})
                          (assoc-in [:selrect :width] 100)
                          (assoc-in [:selrect :height] 100))
        overlay-frame (-> (cts/setup-shape {:type :frame})
                          (assoc-in [:selrect :width] 30)
                          (assoc-in [:selrect :height] 20))
        objects       {(:id base-frame) base-frame
                       (:id overlay-frame) overlay-frame}

        i1 ctsi/default-interaction
        i2 (ctsi/set-action-type i1 :open-overlay)
        i3 (-> i1
               (ctsi/set-action-type :open-overlay)
               (ctsi/set-destination (:id overlay-frame)))]

    (t/testing "Has overlay options"
      (t/is (not (ctsi/has-overlay-opts i1)))
      (t/is (ctsi/has-overlay-opts i2)))

    (t/testing "Set overlay-pos-type without destination"
      (let [new-interaction (ctsi/set-overlay-pos-type i2 :top-right base-frame objects)]
        (t/is (= :top-right (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set overlay-pos-type with destination and auto"
      (let [new-interaction (ctsi/set-overlay-pos-type i3 :bottom-right base-frame objects)]
        (t/is (= :bottom-right (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction)))))

    (t/testing "Set overlay-pos-type with destination and manual"
      (let [new-interaction (ctsi/set-overlay-pos-type i3 :manual base-frame objects)]
        (t/is (= :manual (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 35 40) (:overlay-position new-interaction)))))

    (t/testing "Toggle overlay-pos-type"
      (let [new-interaction (ctsi/toggle-overlay-pos-type i3 :center base-frame objects)
            new-interaction-2 (ctsi/toggle-overlay-pos-type new-interaction :center base-frame objects)
            new-interaction-3 (ctsi/toggle-overlay-pos-type new-interaction-2 :top-right base-frame objects)]
        (t/is (= :manual (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 35 40) (:overlay-position new-interaction)))
        (t/is (= :center (:overlay-pos-type new-interaction-2)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction-2)))
        (t/is (= :top-right (:overlay-pos-type new-interaction-3)))
        (t/is (= (gpt/point 0 0) (:overlay-position new-interaction-3)))))

    (t/testing "Set overlay-position"
      (let [new-interaction (ctsi/set-overlay-position i3 (gpt/point 50 60))]
        (t/is (= :manual (:overlay-pos-type new-interaction)))
        (t/is (= (gpt/point 50 60) (:overlay-position new-interaction)))))

    (t/testing "Set close-click-outside"
      (let [new-interaction (ctsi/set-close-click-outside i3 true)]
        (t/is (not (:close-click-outside i3)))
        (t/is (:close-click-outside new-interaction))))

    (t/testing "Set background-overlay"
      (let [new-interaction (ctsi/set-background-overlay i3 true)]
        (t/is (not (:background-overlay i3)))
        (t/is (:background-overlay new-interaction))))

    (t/testing "Set relative-to"
      (let [relative-to-id (uuid/random)
            new-interaction (ctsi/set-position-relative-to i3 relative-to-id)]
        (t/is (= relative-to-id (:position-relative-to new-interaction)))))))

(defn setup-selrect [{:keys [x y width height] :as obj}]
  (let [rect    (grc/make-rect x y width height)
        center  (grc/rect->center rect)
        points  (grc/rect->points rect)]
    (-> obj
        (assoc :selrect rect)
        (assoc :points points))))

(t/deftest calc-overlay-position
  (let [base-frame    (cts/setup-shape
                       {:type :frame
                        :width 100
                        :height 100})
        popup         (cts/setup-shape
                       {:type :frame
                        :width 50
                        :height 50
                        :x 10
                        :y 10})
        rect         (cts/setup-shape
                      {:type :rect
                       :width 50
                       :height 50
                       :x 10
                       :y 10})

        overlay-frame (cts/setup-shape
                       {:type :frame
                        :width 30
                        :height 20})

        objects       {(:id base-frame) base-frame
                       (:id popup) popup
                       (:id overlay-frame) overlay-frame}

        frame-offset (gpt/point 5 5)

        interaction (-> ctsi/default-interaction
                        (ctsi/set-action-type :open-overlay)
                        (ctsi/set-destination (:id overlay-frame)))
        interaction-auto (ctsi/set-position-relative-to interaction nil)
        interaction-base-frame (ctsi/set-position-relative-to interaction (:id base-frame))
        interaction-popup (ctsi/set-position-relative-to interaction (:id popup))
        interaction-rect (ctsi/set-position-relative-to interaction (:id rect))]
    (t/testing "Overlay top-left relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :top-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (= (:x overlay-pos) 0))
        (t/is (= (:y overlay-pos) 0))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-center relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :top-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 0))
        (t/is (= snap-v :top))
        (t/is (= snap-h :center))))

    (t/testing "Overlay top-right relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :top-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 70))
        (t/is (mth/close? (:y overlay-pos) 0))
        (t/is (= snap-v :top))
        (t/is (= snap-h :right))))

    (t/testing "Overlay bottom-left relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :bottom-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 0))
        (t/is (mth/close? (:y overlay-pos) 80))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :left))))

    (t/testing "Overlay bottom-center relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :bottom-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 80))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :center))))

    (t/testing "Overlay bottom-right relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :bottom-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 70))
        (t/is (mth/close? (:y overlay-pos) 80))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :right))))

    (t/testing "Overlay center relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 40))
        (t/is (= snap-v :center))
        (t/is (= snap-h :center))))

    (t/testing "Overlay manual relative to auto"
      (let [i2 (ctsi/set-overlay-pos-type interaction-auto :center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 40))
        (t/is (= snap-v :center))
        (t/is (= snap-h :center))))

    (t/testing "Overlay manual relative to auto"
      (let [i2 (-> interaction-auto
                   (ctsi/set-overlay-pos-type :manual base-frame objects)
                   (ctsi/set-overlay-position (gpt/point 12 62)))
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 17))
        (t/is (mth/close? (:y overlay-pos) 67))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-left relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :top-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 5))
        (t/is (mth/close? (:y overlay-pos) 5))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-center relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :top-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 40))
        (t/is (mth/close? (:y overlay-pos) 5))
        (t/is (= snap-v :top))
        (t/is (= snap-h :center))))

    (t/testing "Overlay top-right relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :top-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 75))
        (t/is (mth/close? (:y overlay-pos) 5))
        (t/is (= snap-v :top))
        (t/is (= snap-h :right))))

    (t/testing "Overlay bottom-left relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :bottom-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 5))
        (t/is (mth/close? (:y overlay-pos) 85))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :left))))

    (t/testing "Overlay bottom-center relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :bottom-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 40))
        (t/is (mth/close? (:y overlay-pos) 85))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :center))))

    (t/testing "Overlay bottom-right relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :bottom-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 75))
        (t/is (mth/close? (:y overlay-pos) 85))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :right))))

    (t/testing "Overlay center relative to base-frame"
      (let [i2 (ctsi/set-overlay-pos-type interaction-base-frame :center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 40))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :center))
        (t/is (= snap-h :center))))

    (t/testing "Overlay manual relative to base-frame"
      (let [i2 (-> interaction-base-frame
                   (ctsi/set-overlay-pos-type :manual base-frame objects)
                   (ctsi/set-overlay-position (gpt/point 12 62)))
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects base-frame base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 17))
        (t/is (mth/close? (:y overlay-pos) 67))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-left relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :top-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 15))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-center relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :top-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :center))))

    (t/testing "Overlay top-right relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :top-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :right))))

    (t/testing "Overlay bottom-left relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :bottom-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 15))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :left))))

    (t/testing "Overlay bottom-center relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :bottom-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :center))))

    (t/testing "Overlay bottom-right relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :bottom-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :right))))

    (t/testing "Overlay center relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 30))
        (t/is (= snap-v :center))
        (t/is (= snap-h :center))))

    (t/testing "Overlay manual relative to popup"
      (let [i2 (-> interaction-popup
                   (ctsi/set-overlay-pos-type :manual base-frame objects)
                   (ctsi/set-overlay-position (gpt/point 12 62)))
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 27))
        (t/is (mth/close? (:y overlay-pos) 77))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-left relative to popup"
      (let [i2 (ctsi/set-overlay-pos-type interaction-popup :top-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects popup base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 15))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))

    (t/testing "Overlay top-center relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :top-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :center))))

    (t/testing "Overlay top-right relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :top-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 15))
        (t/is (= snap-v :top))
        (t/is (= snap-h :right))))

    (t/testing "Overlay bottom-left relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :bottom-left base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 15))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :left))))

    (t/testing "Overlay bottom-center relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :bottom-center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :center))))

    (t/testing "Overlay bottom-right relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :bottom-right base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 35))
        (t/is (mth/close? (:y overlay-pos) 45))
        (t/is (= snap-v :bottom))
        (t/is (= snap-h :right))))

    (t/testing "Overlay center relative to rect"
      (let [i2 (ctsi/set-overlay-pos-type interaction-rect :center base-frame objects)
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 25))
        (t/is (mth/close? (:y overlay-pos) 30))
        (t/is (= snap-v :center))
        (t/is (= snap-h :center))))

    (t/testing "Overlay manual relative to rect"
      (let [i2 (-> interaction-rect
                   (ctsi/set-overlay-pos-type :manual base-frame objects)
                   (ctsi/set-overlay-position (gpt/point 12 62)))
            [overlay-pos [snap-v snap-h]] (ctsi/calc-overlay-position i2 rect objects rect base-frame overlay-frame frame-offset)]
        (t/is (mth/close? (:x overlay-pos) 17))
        (t/is (mth/close? (:y overlay-pos) 67))
        (t/is (= snap-v :top))
        (t/is (= snap-h :left))))))


(t/deftest animation-checks
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-action-type i1 :open-overlay)
        i3 (ctsi/set-action-type i1 :toggle-overlay)
        i4 (ctsi/set-action-type i1 :close-overlay)
        i5 (ctsi/set-action-type i1 :prev-screen)
        i6 (ctsi/set-action-type i1 :open-url)]

    (t/testing "Has animation?"
      (t/is (ctsi/has-animation? i1))
      (t/is (ctsi/has-animation? i2))
      (t/is (ctsi/has-animation? i3))
      (t/is (ctsi/has-animation? i4))
      (t/is (not (ctsi/has-animation? i5)))
      (t/is (not (ctsi/has-animation? i6))))

    (t/testing "Valid push?"
      (t/is (ctsi/allow-push? (:action-type i1)))
      (t/is (not (ctsi/allow-push? (:action-type i2))))
      (t/is (not (ctsi/allow-push? (:action-type i3))))
      (t/is (not (ctsi/allow-push? (:action-type i4))))
      (t/is (not (ctsi/allow-push? (:action-type i5))))
      (t/is (not (ctsi/allow-push? (:action-type i6)))))))


(t/deftest set-animation-type
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type i1 :dissolve)]

    (t/testing "Set animation type nil"
      (let [new-interaction
            (ctsi/set-animation-type i1 nil)]
        (t/is (nil? (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type unchanged"
      (let [new-interaction
            (ctsi/set-animation-type i2 :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type changed"
      (let [new-interaction
            (ctsi/set-animation-type i2 :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))))

    (t/testing "Set animation type reset"
      (let [new-interaction
            (ctsi/set-animation-type i2 nil)]
        (t/is (nil? (-> new-interaction :animation)))))

    (t/testing "Set animation type dissolve"
      (let [new-interaction
            (ctsi/set-animation-type i1 :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))))

    (t/testing "Set animation type dissolve with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :slide
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (ctsi/set-animation-type interaction :dissolve)]
        (t/is (= :dissolve (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))))

    (t/testing "Set animation type slide"
      (let [new-interaction
            (ctsi/set-animation-type i1 :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))
        (t/is (= :in (-> new-interaction :animation :way)))
        (t/is (= :right (-> new-interaction :animation :direction)))
        (t/is (= false (-> new-interaction :animation :offset-effect)))))

    (t/testing "Set animation type slide with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :dissolve
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (ctsi/set-animation-type interaction :slide)]
        (t/is (= :slide (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))
        (t/is (= :out (-> new-interaction :animation :way)))
        (t/is (= :left (-> new-interaction :animation :direction)))
        (t/is (= true (-> new-interaction :animation :offset-effect)))))

    (t/testing "Set animation type push"
      (let [new-interaction
            (ctsi/set-animation-type i1 :push)]
        (t/is (= :push (-> new-interaction :animation :animation-type)))
        (t/is (= 300 (-> new-interaction :animation :duration)))
        (t/is (= :linear (-> new-interaction :animation :easing)))
        (t/is (= :right (-> new-interaction :animation :direction)))))

    (t/testing "Set animation type push with previous data"
      (let [interaction (assoc i1 :animation {:animation-type :slide
                                              :duration 1000
                                              :easing :ease-out
                                              :way :out
                                              :direction :left
                                              :offset-effect true})
            new-interaction
            (ctsi/set-animation-type interaction :push)]
        (t/is (= :push (-> new-interaction :animation :animation-type)))
        (t/is (= 1000 (-> new-interaction :animation :duration)))
        (t/is (= :ease-out (-> new-interaction :animation :easing)))
        (t/is (= :left (-> new-interaction :animation :direction)))))))


(t/deftest allowed-animation
  (let [i1 (ctsi/set-action-type ctsi/default-interaction :open-overlay)
        i2 (ctsi/set-action-type ctsi/default-interaction :close-overlay)
        i3 (ctsi/set-action-type ctsi/default-interaction :toggle-overlay)]

    (t/testing "Cannot use animation push for an overlay action"
      (let [bad-interaction-1 (assoc i1 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})
            bad-interaction-2 (assoc i2 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})
            bad-interaction-3 (assoc i3 :animation {:animation-type :push
                                                    :duration 1000
                                                    :easing :ease-out
                                                    :direction :left})]
        (t/is (not (ctsi/allowed-animation? (:action-type bad-interaction-1)
                                            (-> bad-interaction-1 :animation :animation-type))))
        (t/is (not (ctsi/allowed-animation? (:action-type bad-interaction-2)
                                            (-> bad-interaction-1 :animation :animation-type))))
        (t/is (not (ctsi/allowed-animation? (:action-type bad-interaction-3)
                                            (-> bad-interaction-1 :animation :animation-type))))))

    (t/testing "Remove animation if moving to an forbidden state"
      (let [interaction (ctsi/set-animation-type ctsi/default-interaction :push)
            new-interaction (ctsi/set-action-type interaction :open-overlay)]
        (t/is (nil? (:animation new-interaction)))))))


(t/deftest option-duration
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type ctsi/default-interaction :dissolve)]

    (t/testing "Has duration?"
      (t/is (not (ctsi/has-duration? i1)))
      (t/is (ctsi/has-duration? i2)))

    (t/testing "Set duration"
      (let [new-interaction (ctsi/set-duration i2 1000)]
        (t/is (= 1000 (-> new-interaction :animation :duration)))))))


(t/deftest option-easing
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type ctsi/default-interaction :dissolve)]

    (t/testing "Has easing?"
      (t/is (not (ctsi/has-easing? i1)))
      (t/is (ctsi/has-easing? i2)))

    (t/testing "Set easing"
      (let [new-interaction (ctsi/set-easing i2 :ease-in)]
        (t/is (= :ease-in (-> new-interaction :animation :easing)))))))


(t/deftest option-way
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type ctsi/default-interaction :slide)
        i3 (ctsi/set-action-type i2 :open-overlay)]

    (t/testing "Has way?"
      (t/is (not (ctsi/has-way? i1)))
      (t/is (ctsi/has-way? i2))
      (t/is (not (ctsi/has-way? i3)))
      (t/is (some? (-> i3 :animation :way)))) ; <- it exists but is ignored

    (t/testing "Set way"
      (let [new-interaction (ctsi/set-way i2 :out)]
        (t/is (= :out (-> new-interaction :animation :way)))))))


(t/deftest option-direction
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type ctsi/default-interaction :push)
        i3 (ctsi/set-animation-type ctsi/default-interaction :dissolve)]

    (t/testing "Has direction?"
      (t/is (not (ctsi/has-direction? i1)))
      (t/is (ctsi/has-direction? i2)))

    (t/testing "Set direction"
      (let [new-interaction (ctsi/set-direction i2 :left)]
        (t/is (= :left (-> new-interaction :animation :direction)))))

    (t/testing "Invert direction"
      (let [a-none (:animation i3)
            a-right (:animation i2)
            a-left (assoc a-right :direction :left)
            a-up (assoc a-right :direction :up)
            a-down (assoc a-right :direction :down)

            a-nil' (ctsi/invert-direction nil)
            a-none' (ctsi/invert-direction a-none)
            a-right' (ctsi/invert-direction a-right)
            a-left' (ctsi/invert-direction a-left)
            a-up' (ctsi/invert-direction a-up)
            a-down' (ctsi/invert-direction a-down)]

        (t/is (nil? a-nil'))
        (t/is (nil? (:direction a-none')))
        (t/is (= :left (:direction a-right')))
        (t/is (= :right (:direction a-left')))
        (t/is (= :down (:direction a-up')))
        (t/is (= :up (:direction a-down')))))))

(t/deftest option-offset-effect
  (let [i1 ctsi/default-interaction
        i2 (ctsi/set-animation-type ctsi/default-interaction :slide)
        i3 (ctsi/set-action-type i2 :open-overlay)]

    (t/testing "Has offset-effect"
      (t/is (not (ctsi/has-offset-effect? i1)))
      (t/is (ctsi/has-offset-effect? i2))
      (t/is (not (ctsi/has-offset-effect? i3)))
      (t/is (some? (-> i3 :animation :offset-effect)))) ; <- it exists but is ignored

    (t/testing "Set offset-effect"
      (let [new-interaction (ctsi/set-offset-effect i2 true)]
        (t/is (= true (-> new-interaction :animation :offset-effect)))))))


(t/deftest modify-interactions
  (let [i1 (ctsi/set-action-type ctsi/default-interaction :open-overlay)
        i2 (ctsi/set-action-type ctsi/default-interaction :close-overlay)
        i3 (ctsi/set-action-type ctsi/default-interaction :prev-screen)
        interactions [i1 i2]]

    (t/testing "Add interaction to nil"
      (let [new-interactions (ctsi/add-interaction nil i3)]
        (t/is (= (count new-interactions) 1))
        (t/is (= (:action-type (last new-interactions)) :prev-screen))))

    (t/testing "Add interaction to normal"
      (let [new-interactions (ctsi/add-interaction interactions i3)]
        (t/is (= (count new-interactions) 3))
        (t/is (= (:action-type (last new-interactions)) :prev-screen))))

    (t/testing "Remove interaction"
      (let [new-interactions (ctsi/remove-interaction interactions 0)]
        (t/is (= (count new-interactions) 1))
        (t/is (= (:action-type (last new-interactions)) :close-overlay))))

    (t/testing "Update interaction"
      (let [new-interactions (ctsi/update-interaction interactions 1 #(ctsi/set-action-type % :open-url))]
        (t/is (= (count new-interactions) 2))
        (t/is (= (:action-type (last new-interactions)) :open-url))))))


(t/deftest remap-interactions
  (let [frame1 (cts/setup-shape {:type :frame})
        frame2 (cts/setup-shape {:type :frame})
        frame3 (cts/setup-shape {:type :frame})
        frame4 (cts/setup-shape {:type :frame})
        frame5 (cts/setup-shape {:type :frame})
        frame6 (cts/setup-shape {:type :frame})

        objects {(:id frame3) frame3
                 (:id frame4) frame4
                 (:id frame5) frame5}

        ids-map {(:id frame1) (:id frame4)
                 (:id frame2) (:id frame5)}

        i1 (ctsi/set-destination ctsi/default-interaction (:id frame1))
        i2 (ctsi/set-destination ctsi/default-interaction (:id frame2))
        i3 (ctsi/set-destination ctsi/default-interaction (:id frame3))
        i4 (ctsi/set-destination ctsi/default-interaction nil)
        i5 (ctsi/set-destination ctsi/default-interaction (:id frame6))

        interactions [i1 i2 i3 i4 i5]]

    (t/testing "Remap interactions"
      (let [new-interactions (ctsi/remap-interactions interactions ids-map objects)]
        (t/is (= (count new-interactions) 4))
        (t/is (= (:id frame4) (:destination (get new-interactions 0))))
        (t/is (= (:id frame5) (:destination (get new-interactions 1))))
        (t/is (= (:id frame3) (:destination (get new-interactions 2))))
        (t/is (nil? (:destination (get new-interactions 3))))))))

