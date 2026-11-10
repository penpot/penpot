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

(t/deftest normalize-def-node-keywordizes-string-tags
  ;; Stored files can hold def node tags as strings; nested nodes too.
  (let [node (svg/normalize-def-node
              {:tag "mask"
               :attrs {:id "m1" :maskUnits "userSpaceOnUse"}
               :content [{:tag "clipPath"
                          :attrs {}
                          :content [{:tag "rect" :attrs {:width "10"} :content []}]}]})]
    (t/is (= :mask (:tag node)))
    (t/is (= :clipPath (get-in node [:content 0 :tag])) "source spelling is kept")
    (t/is (= :rect (get-in node [:content 0 :content 0 :tag])))
    (t/is (= "userSpaceOnUse" (get-in node [:attrs :mask-units])))))

(t/deftest kebabize-content-node-keywordizes-string-tags
  (let [node (svg/kebabize-content-node
              {:tag "text"
               :attrs {:fontSize "12"}
               :content ["Hi" {:tag "tspan" :attrs {} :content ["there"]}]})]
    (t/is (= :text (:tag node)))
    (t/is (= "12" (get-in node [:attrs :font-size])))
    (t/is (= "Hi" (get-in node [:content 0])) "text children are left as they are")
    (t/is (= :tspan (get-in node [:content 1 :tag])))))

(t/deftest normalize-def-node-keeps-keyword-tags
  (let [node {:tag :linearGradient :attrs {:id "g"} :content []}]
    (t/is (= node (svg/normalize-def-node node)))))

(t/deftest normalize-nodes-turn-nil-attrs-into-empty-map
  (t/is (= {} (:attrs (svg/kebabize-content-node {:tag :style :attrs nil :content [".a{}"]}))))
  (t/is (= {} (:attrs (svg/normalize-def-node {:tag :g :attrs nil :content []}))))
  (t/is (not (contains? (svg/kebabize-content-node {:tag :g :content []}) :attrs))
        "absent attrs stay absent"))
