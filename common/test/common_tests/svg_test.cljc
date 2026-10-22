;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.svg-test
  (:require
   [app.common.data :as d]
   [app.common.svg :as svg]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/deftest clean-attrs-1
  (let [attrs  {:class "foobar"}
        result (svg/attrs->props attrs)]
    (t/is (= result {:className "foobar"}))))

(t/deftest clean-attrs-2
  (let [attrs  {:overline-position "top"
                :style {:fill "none"
                        :stroke-dashoffset 1}}
        result (svg/attrs->props attrs true)]
    (t/is (= result {:overlinePosition "top", :style {:fill "none", :strokeDashoffset 1}}))))

(t/deftest clean-attrs-3
  (let [attrs  {:overline-position "top"
                :style (str "fill:#00801b;fill-opacity:1;stroke:none;stroke-width:2749.72;"
                            "stroke-linecap:round;stroke-dasharray:none;stop-color:#000000")}
        result (svg/attrs->props attrs true)]
    (t/is (= result {:overlinePosition "top",
                     :style {:fill "#00801b",
                             :fillOpacity "1",
                             :stroke "none",
                             :strokeWidth "2749.72",
                             :strokeLinecap "round",
                             :strokeDasharray "none",
                             :stopColor "#000000"}}))))

(t/deftest extract-defs-keeps-fe-drop-shadow
  (let [[defs _] (svg/extract-defs {:tag :filter
                                    :attrs {:id "shadow"}
                                    :content [{:tag :feDropShadow}]})]
    (t/is (= [:feDropShadow] (mapv :tag (get-in defs ["shadow" :content]))))))

(t/deftest stored-attr-display-name-uses-spec-spelling
  ;; The svg-attrs menu shows these labels: kebab storage matches the
  ;; spec for most keys, but spec-camel keys show their source spelling.
  (t/is (= "class" (svg/stored-attr-display-name :class-name)))
  (t/is (= "viewBox" (svg/stored-attr-display-name :view-box)))
  (t/is (= "stroke-width" (svg/stored-attr-display-name :stroke-width)))
  (t/is (= "stdDeviation" (svg/stored-attr-display-name :std-deviation)))
  (t/is (= "fill-rule" (svg/stored-attr-display-name :fill-rule)))
  (t/is (= "data-foo" (svg/stored-attr-display-name :data-foo))
        "unknown keys fall back to their name"))

(t/deftest stored-attr-display-names-round-trip
  ;; Every whitelist source key maps back to its own spelling: guards
  ;; against two source keys sharing one stored key (last-wins would
  ;; silently mislabel one of them).
  (doseq [k (concat svg/svg-attrs
                    svg/svg-presentation-attrs
                    svg/penpot-extra-attrs)]
    (let [stored ((comp keyword str/kebab name) (svg/prop-key k))]
      (t/is (= (name k) (svg/stored-attr-display-name stored))
            (str "display round-trip for " k)))))
