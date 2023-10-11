;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.svg-test
  (:require
   [app.common.data :as d]
   [app.common.svg :as svg]
   [clojure.test :as t]))

(t/deftest clean-attrs-1
  (let [attrs  {:class "foobar"}
        result (svg/clean-attrs attrs)]
    (t/is (= result {:className "foobar"}))))

(t/deftest clean-attrs-2
  (let [attrs  {:overline-position "top"
                :style {:fill "none"
                        :stroke-dashoffset 1}}
        result (svg/clean-attrs attrs true)]
    (t/is (= result {:overlinePosition "top", :style {:fill "none", :strokeDashoffset 1}}))))

(t/deftest clean-attrs-3
  (let [attrs  {:overline-position "top"
                :style (str "fill:#00801b;fill-opacity:1;stroke:none;stroke-width:2749.72;"
                            "stroke-linecap:round;stroke-dasharray:none;stop-color:#000000")}
        result (svg/clean-attrs attrs true)]
    (t/is (= result {:overlinePosition "top",
                     :style {:fill "#00801b",
                             :fillOpacity "1",
                             :stroke "none",
                             :strokeWidth "2749.72",
                             :strokeLinecap "round",
                             :strokeDasharray "none",
                             :stopColor "#000000"}}))))

