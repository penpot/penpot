;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.gradient-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as clr]
   [app.config :as cf]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.gradient :as wtg]
   [cljs.test :as t :include-macros true]))

(def ^:private stops
  [{:color "#ff0000" :position 0}
   {:color "#0000ff" :position 1}])

;; The values are the ones the tokenscript gradient token type resolves:
;; its angle in degrees, its positions from 0 to 1 and its colors in hex.
(t/deftest value->gradient-test
  (t/testing "a linear gradient without an angle goes from top to bottom"
    (t/is (= {:start-x 0.5 :start-y 0 :end-x 0.5 :end-y 1}
             (-> (wtg/value->gradient {:kind "linear" :stops stops})
                 (select-keys [:start-x :start-y :end-x :end-y])))))

  (t/testing "a linear gradient runs along its angle"
    (t/are [angle points] (= points
                             (-> (wtg/value->gradient {:kind "linear" :angle angle :stops stops})
                                 (select-keys [:start-x :start-y :end-x :end-y])))
      180 {:start-x 0.5 :start-y 0 :end-x 0.5 :end-y 1}
      0   {:start-x 0.5 :start-y 1 :end-x 0.5 :end-y 0}
      90  {:start-x 0 :start-y 0.5 :end-x 1 :end-y 0.5}
      270 {:start-x 1 :start-y 0.5 :end-x 0 :end-y 0.5}
      45  {:start-x 0 :start-y 1 :end-x 1 :end-y 0}
      135 {:start-x 0 :start-y 0 :end-x 1 :end-y 1}
      -90 {:start-x 1 :start-y 0.5 :end-x 0 :end-y 0.5}))

  (t/testing "a radial gradient goes from the center to the edge"
    (t/is (= {:type :radial :start-x 0.5 :start-y 0.5 :end-x 0.5 :end-y 1 :width 1}
             (-> (wtg/value->gradient {:kind "radial" :stops stops})
                 (dissoc :stops)))))

  (t/testing "stop colors become a hex color and an opacity"
    (t/is (= [{:color "#ff0000" :opacity 1 :offset 0}
              {:color "#0000ff" :opacity 0.5019607843137255 :offset 0.25}
              {:color "#00ff00" :opacity 1 :offset 1}]
             (:stops (wtg/value->gradient
                      {:kind "linear"
                       :stops [{:color "#ff0000" :position 0}
                               {:color "#0000ff80" :position 0.25}
                               {:color "#00ff00ff" :position 1}]})))))

  (t/testing "stops are sorted by position"
    (t/is (= [0 1]
             (->> (wtg/value->gradient {:kind "linear" :stops (vec (reverse stops))})
                  :stops
                  (map :offset)))))

  (t/testing "the result is a gradient a fill accepts"
    (t/is (sm/validate clr/schema:gradient
                       (wtg/value->gradient {:kind "linear" :angle 33 :stops stops})))
    (t/is (sm/validate clr/schema:gradient
                       (wtg/value->gradient {:kind "radial" :stops stops}))))

  (t/testing "anything but a gradient has no fill"
    (t/is (nil? (wtg/value->gradient nil)))
    (t/is (nil? (wtg/value->gradient "#ff0000")))
    (t/is (nil? (wtg/value->gradient {:kind "linear"})))))

(t/deftest paints-test
  (let [value    {:kind "linear" :angle 90 :stops stops}
        gradient (wtg/value->gradient value)]
    (t/testing "a gradient value paints the gradient it makes"
      (t/is (wtg/paints? value {:gradient gradient :opacity 1})))

    (t/testing "a gradient value doesn't paint another gradient or a color"
      (t/is (not (wtg/paints? value {:gradient (wtg/value->gradient (assoc value :angle 180))
                                     :opacity 1})))
      (t/is (not (wtg/paints? value {:color "#ff0000" :opacity 1}))))

    (t/testing "a color value paints its color only"
      (t/is (wtg/paints? "#ff0000" {:color "#ff0000" :opacity 1}))
      (t/is (not (wtg/paints? "#ff0000" {:gradient gradient :opacity 1}))))

    (t/testing "no value paints nothing"
      (t/is (not (wtg/paints? nil {:gradient gradient})))
      (t/is (not (wtg/paints? nil {:image {:id 1}})))
      (t/is (not (wtg/paints? {:kind "linear"} {:image {:id 1}}))))))

(t/deftest paint-tokens-test
  (let [color          {:name "red" :type :color}
        gradient       {:name "brand" :type :gradient}
        tokens-by-type {:color [color] :gradient [gradient] :spacing [{:name "sm"}]}]
    (t/testing "gradient tokens fill a shape with both of their flags"
      (with-redefs [cf/flags (conj cf/flags :token-gradient :tokenscript)]
        (t/is (wtg/enabled?))
        (t/is (= [color gradient] (wtg/paint-tokens tokens-by-type)))
        (t/is (wtg/paint-token? gradient))))

    (t/testing "gradient tokens are left out without one of their flags"
      (with-redefs [cf/flags (-> cf/flags (conj :token-gradient) (disj :tokenscript))]
        (t/is (not (wtg/enabled?)))
        (t/is (= [color] (wtg/paint-tokens tokens-by-type)))
        (t/is (not (wtg/paint-token? gradient)))
        (t/is (wtg/paint-token? color)))
      (with-redefs [cf/flags (-> cf/flags (disj :token-gradient) (conj :tokenscript))]
        (t/is (= [color] (wtg/paint-tokens tokens-by-type)))))))

(t/deftest value->paint-test
  (t/testing "a gradient value paints a gradient"
    (t/is (= {:gradient (wtg/value->gradient {:kind "radial" :stops stops})
              :opacity 1}
             (dwta/value->paint {:kind "radial" :stops stops}))))

  (t/testing "a color value paints a color"
    (t/is (= {:color "#ff0000" :opacity 0.5}
             (dwta/value->paint "rgba(255, 0, 0, 0.5)"))))

  (t/testing "anything else paints nothing"
    (t/is (nil? (dwta/value->paint nil)))))

(t/deftest format-angle-and-position-test
  (t/testing "a resolved angle is written in degrees"
    (t/is (= "90deg" (dwtf/format-angle 90)))
    (t/is (= "57.3deg" (dwtf/format-angle (/ 180 js/Math.PI)))))

  (t/testing "a resolved position is written as a percentage"
    (t/is (= "50%" (dwtf/format-position 0.5)))
    (t/is (= "66.67%" (dwtf/format-position 0.6667)))
    (t/is (= "5000%" (dwtf/format-position 50))))

  (t/testing "a token value is written as it is"
    (t/is (= "90" (dwtf/format-angle "90")))
    (t/is (= "{stop.mid}" (dwtf/format-position "{stop.mid}")))))

(t/deftest format-gradient-test
  (t/testing "a gradient value is written on one line"
    (t/is (= "linear 90: {color.brand} 0, #0000ff 1"
             (dwtf/format-token-value {:kind "linear"
                                       :angle "90"
                                       :stops [{:color "{color.brand}" :position "0"}
                                               {:color "#0000ff" :position "1"}]})))
    (t/is (= "radial: #ff0000 0%, #0000ff 100%"
             (dwtf/format-token-value {:kind "radial" :stops stops}))))

  (t/testing "a resolved gradient is written in degrees and percentages, as its form shows it"
    (t/is (= "linear 45deg: #ff0000 0%, #0000ff 50%, #00ff00 66.67%"
             (dwtf/format-token-value {:kind "linear"
                                       :angle 45
                                       :stops [{:color "#ff0000" :position 0}
                                               {:color "#0000ff" :position 0.5}
                                               {:color "#00ff00" :position 0.6667}]})))))
