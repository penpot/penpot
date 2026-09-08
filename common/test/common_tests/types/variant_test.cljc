;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.variant-test
  (:require
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(t/deftest variant-component
  (t/is (not (ctv/variant-component? nil)))
  (t/is (not (ctv/variant-component? {})))
  (t/is (ctv/variant-component? {:variant-id (uuid/next)})))

(t/deftest variant-shape
  (t/is (not (ctv/variant-shape? nil)))
  (t/is (not (ctv/variant-shape? {})))
  (t/is (ctv/variant-shape? {:variant-id (uuid/next)})))

(t/deftest variant-container
  (t/is (not (ctv/variant-container? nil)))
  (t/is (not (ctv/variant-container? {})))
  (t/is (ctv/variant-container? {:is-variant-container true})))

(t/deftest properties-to-name-test
  (t/is (= "" (ctv/properties-to-name [])))
  (t/is (= "" (ctv/properties-to-name nil)))
  (t/is (= "Button, Primary" (ctv/properties-to-name [{:name "Property 1" :value "Button"}
                                                      {:name "Property 2" :value "Primary"}])))
  (t/is (= "Button" (ctv/properties-to-name [{:name "Property 1" :value "Button"}
                                             {:name "Property 2" :value ""}]))))

(t/deftest next-property-number-test
  (t/is (= 1 (ctv/next-property-number [])))
  (t/is (= 1 (ctv/next-property-number nil)))
  (t/is (= 2 (ctv/next-property-number [{:name "Property 1" :value "x"}])))
  (t/is (= 4 (ctv/next-property-number [{:name "Property 3" :value "x"}])))
  (t/is (= 3 (ctv/next-property-number [{:name "Property 1" :value "x"}
                                        {:name "Property 2" :value "y"}]))))

(t/deftest add-new-property-test
  (t/is (= [{:name "Property 1" :value "x"}]
           (ctv/add-new-property [] "x")))
  (t/is (= [{:name "Property 1" :value "x"}]
           (ctv/add-new-property nil "x")))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "y"}]
           (ctv/add-new-property [{:name "Property 1" :value "x"}] "y"))))

(t/deftest add-new-properties-test
  (t/is (= [{:name "Property 1" :value "a"} {:name "Property 2" :value "b"}]
           (ctv/add-new-properties [] ["a" "b"])))
  (t/is (= '({:name "Property 2" :value "b"} {:name "Property 1" :value "a"})
           (ctv/add-new-properties nil ["a" "b"])))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "a"} {:name "Property 3" :value "b"}]
           (ctv/add-new-properties [{:name "Property 1" :value "x"}] ["a" "b"]))))

(t/deftest path-to-properties-test
  (t/is (= [] (ctv/path-to-properties "" [])))
  (t/is (= [{:name "Property 1" :value "a"} {:name "Property 2" :value "b"}]
           (ctv/path-to-properties "a / b" nil)))
  (t/is (= [{:name "Property 1" :value "Button"}
            {:name "Property 2" :value "Primary"}
            {:name "Property 3" :value "Hover"}]
           (ctv/path-to-properties "Button / Primary / Hover" [])))
  (t/is (= [{:name "Property 1" :value "Button"}
            {:name "Property 2" :value "Primary"}
            {:name "Property 3" :value "Hover"}
            {:name "Property 4" :value ""}]
           (ctv/path-to-properties "Button / Primary / Hover" [] 4)))
  (t/is (= [{:name "Property 1" :value "Button"}
            {:name "Property 2" :value "Primary"}]
           (ctv/path-to-properties "Button / Primary" [{:name "Property 1" :value "old"}
                                                       {:name "Property 2" :value "old2"}]))))

(t/deftest properties-map->formula-test
  (t/is (= "" (ctv/properties-map->formula [])))
  (t/is (= "" (ctv/properties-map->formula nil)))
  (t/is (= "Property 1=Button, Property 2=Primary"
           (ctv/properties-map->formula [{:name "Property 1" :value "Button"}
                                         {:name "Property 2" :value "Primary"}])))
  (t/is (= "Property 1=Button"
           (ctv/properties-map->formula [{:name "Property 1" :value "Button"}
                                         {:name "Property 2" :value ""}]))))

(t/deftest properties-formula->map-test
  (t/is (= [] (ctv/properties-formula->map "")))
  (t/is (= [] (ctv/properties-formula->map nil)))
  (t/is (= [{:name "Property 1" :value "Button"} {:name "Property 2" :value "Primary"}]
           (ctv/properties-formula->map "Property 1=Button, Property 2=Primary")))
  (t/is (= [{:name "Property 1" :value "Button"}]
           (ctv/properties-formula->map "Property 1=Button, Property 2="))))

(t/deftest valid-properties-formula?-test
  (t/is (= true (ctv/valid-properties-formula? "Property 1=Button, Property 2=Primary")))
  (t/is (= false (ctv/valid-properties-formula? "")))
  (t/is (= true (ctv/valid-properties-formula? nil)))
  (t/is (= false (ctv/valid-properties-formula? "Property 1=Button, Property 2"))))

(t/deftest find-properties-to-remove-test
  (t/is (= [] (ctv/find-properties-to-remove [] [])))
  (t/is (= [] (ctv/find-properties-to-remove nil nil)))
  (t/is (= [{:name "Property 3" :value "z"}]
           (ctv/find-properties-to-remove [{:name "Property 1" :value "x"}
                                           {:name "Property 2" :value "y"}
                                           {:name "Property 3" :value "z"}]
                                          [{:name "Property 1" :value "x"}
                                           {:name "Property 2" :value "y"}])))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "y"}]
           (ctv/find-properties-to-remove [{:name "Property 1" :value "x"}
                                           {:name "Property 2" :value "y"}]
                                          [{:name "Property 3" :value "z"}]))))

(t/deftest find-properties-to-update-test
  (t/is (= [] (ctv/find-properties-to-update [] [])))
  (t/is (= [] (ctv/find-properties-to-update nil nil)))
  (t/is (= [{:name "Property 1" :value "new-x"}]
           (ctv/find-properties-to-update [{:name "Property 1" :value "x"}
                                           {:name "Property 2" :value "y"}]
                                          [{:name "Property 1" :value "new-x"}
                                           {:name "Property 2" :value "y"}])))
  (t/is (= [{:name "Property 1" :value "new-x"} {:name "Property 2" :value "new-y"}]
           (ctv/find-properties-to-update [{:name "Property 1" :value "x"}
                                           {:name "Property 2" :value "y"}]
                                          [{:name "Property 1" :value "new-x"}
                                           {:name "Property 2" :value "new-y"}]))))

(t/deftest find-properties-to-add-test
  (t/is (= [] (ctv/find-properties-to-add [] [])))
  (t/is (= [] (ctv/find-properties-to-add nil nil)))
  (t/is (= [{:name "Property 3" :value "z"}]
           (ctv/find-properties-to-add [{:name "Property 1" :value "x"}
                                        {:name "Property 2" :value "y"}]
                                       [{:name "Property 1" :value "x"}
                                        {:name "Property 2" :value "y"}
                                        {:name "Property 3" :value "z"}])))
  (t/is (= [{:name "Property 2" :value "y"}]
           (ctv/find-properties-to-add [{:name "Property 1" :value "x"}]
                                       [{:name "Property 1" :value "x"}
                                        {:name "Property 2" :value "y"}]))))

(t/deftest update-number-in-repeated-item-test
  (t/is (= "Property" (ctv/update-number-in-repeated-item [] "Property")))
  (t/is (= "Property" (ctv/update-number-in-repeated-item nil "Property")))
  (t/is (= "Property (1)" (ctv/update-number-in-repeated-item ["Property"] "Property")))
  (t/is (= "Property (2)" (ctv/update-number-in-repeated-item ["Property" "Property (1)"] "Property")))
  (t/is (= "Property" (ctv/update-number-in-repeated-item ["Other"] "Property"))))

(t/deftest update-number-in-repeated-prop-names-test
  (t/is (= [] (ctv/update-number-in-repeated-prop-names [])))
  (t/is (= [] (ctv/update-number-in-repeated-prop-names nil)))
  (t/is (= [{:name "Property" :value "x"}]
           (ctv/update-number-in-repeated-prop-names [{:name "Property" :value "x"}])))
  (t/is (= [{:name "Property" :value "x"} {:name "Property (1)" :value "y"}]
           (ctv/update-number-in-repeated-prop-names [{:name "Property" :value "x"}
                                                      {:name "Property" :value "y"}])))
  (t/is (= [{:name "Property" :value "x"} {:name "Property (1)" :value "y"} {:name "Property (2)" :value "z"}]
           (ctv/update-number-in-repeated-prop-names [{:name "Property" :value "x"}
                                                      {:name "Property" :value "y"}
                                                      {:name "Property" :value "z"}]))))

(t/deftest find-index-for-property-name-test
  (t/is (= nil (ctv/find-index-for-property-name [] "Property 1")))
  (t/is (= nil (ctv/find-index-for-property-name nil "Property 1")))
  (t/is (= 0 (ctv/find-index-for-property-name [{:name "Property 1" :value "x"}] "Property 1")))
  (t/is (= 1 (ctv/find-index-for-property-name [{:name "Property 1" :value "x"}
                                                {:name "Property 2" :value "y"}] "Property 2")))
  (t/is (= nil (ctv/find-index-for-property-name [{:name "Property 1" :value "x"}] "Property 3"))))

(t/deftest remove-prefix-test
  (t/is (= "name" (ctv/remove-prefix "name" "")))
  (t/is (= "name" (ctv/remove-prefix "name" nil)))
  (t/is (= "Primary" (ctv/remove-prefix "Button / Primary" "Button")))
  (t/is (= "Primary" (ctv/remove-prefix "Button / Primary" "Button / ")))
  (t/is (= "Button / Primary" (ctv/remove-prefix "Button / Primary" "Other"))))

(t/deftest merge-properties-test
  (t/is (= [] (ctv/merge-properties [] [])))
  (t/is (= [] (ctv/merge-properties nil nil)))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "y"}]
           (ctv/merge-properties [{:name "Property 1" :value "a"}
                                  {:name "Property 2" :value "b"}]
                                 [{:name "Property 1" :value "x"}
                                  {:name "Property 2" :value "y"}])))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "y"} {:name "Property 3" :value "z"}]
           (ctv/merge-properties [{:name "Property 1" :value "a"}
                                  {:name "Property 2" :value "b"}]
                                 [{:name "Property 1" :value "x"}
                                  {:name "Property 2" :value "y"}
                                  {:name "Property 3" :value "z"}])))
  (t/is (= [{:name "Property 1" :value "a"} {:name "Property 2" :value "y"}]
           (ctv/merge-properties [{:name "Property 1" :value "a"}
                                  {:name "Property 2" :value "b"}]
                                 [{:name "Property 2" :value "y"}]))))

(t/deftest compare-properties-test
  (t/is (= [] (ctv/compare-properties [])))
  (t/is (= [] (ctv/compare-properties nil)))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "y"}]
           (ctv/compare-properties [[{:name "Property 1" :value "x"}
                                     {:name "Property 2" :value "y"}]])))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value nil}]
           (ctv/compare-properties [[{:name "Property 1" :value "x"}
                                     {:name "Property 2" :value "y"}]
                                    [{:name "Property 1" :value "x"}
                                     {:name "Property 2" :value "z"}]])))
  (t/is (= [{:name "Property 1" :value "x"} {:name "Property 2" :value "*"}]
           (ctv/compare-properties [[{:name "Property 1" :value "x"}
                                     {:name "Property 2" :value "y"}]
                                    [{:name "Property 1" :value "x"}
                                     {:name "Property 2" :value "z"}]]
                                   "*"))))

(t/deftest variant-name-to-name-test
  (t/is (= "Button / Primary / Hover" (ctv/variant-name-to-name {:name "Button" :variant-name "Primary, Hover"})))
  (t/is (= "Button" (ctv/variant-name-to-name {:name "Button" :variant-name ""})))
  (t/is (= "Button" (ctv/variant-name-to-name {:name "Button" :variant-name nil})))
  (t/is (= "" (ctv/variant-name-to-name {:name "" :variant-name ""})))
  (t/is (= nil (ctv/variant-name-to-name {:name nil :variant-name nil}))))

(t/deftest find-boolean-pair-test
  (t/is (= {"on" true "off" false} (ctv/find-boolean-pair ["on" "off"])))
  (t/is (= {"yes" true "no" false} (ctv/find-boolean-pair ["yes" "no"])))
  (t/is (= {"true" true "false" false} (ctv/find-boolean-pair ["true" "false"])))
  (t/is (= {"on" true "off" false} (ctv/find-boolean-pair ["off" "on"])))
  (t/is (= {"ON" true "OFF" false} (ctv/find-boolean-pair ["ON" "OFF"])))
  (t/is (= nil (ctv/find-boolean-pair ["foo" "bar"])))
  (t/is (= nil (ctv/find-boolean-pair nil)))
  (t/is (= nil (ctv/find-boolean-pair ["on"]))))

(t/deftest same-variant?-test
  (t/is (= false (ctv/same-variant? [])))
  (t/is (= false (ctv/same-variant? nil)))
  (t/is (= true (ctv/same-variant? [{:variant-id "abc"}])))
  (t/is (= true (ctv/same-variant? [{:variant-id "abc"} {:variant-id "abc"}])))
  (t/is (= false (ctv/same-variant? [{:variant-id "abc"} {:variant-id "def"}])))
  (t/is (= false (ctv/same-variant? [{:variant-id ""} {:variant-id ""}]))))

(t/deftest properties-distance01
  ;;c1: primary, default, rounded, blue, dark
  ;;c2: primary, hover, squared, blue, dark
  ;;c3: primary, default, squared, blue, light

  ;; I have a copy of c1, and I change from rounded to squared
  ;; c2: 1 difference in pos 2
  ;; c3: 1 differences in pos 5
  ;; The min distance should be c3

  (let [target [{:name "type" :value "primary"}
                {:name "status" :value "default"}
                {:name "borders" :value "squared"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        props2 [{:name "type" :value "primary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        props3 [{:name "type" :value "primary"}
                {:name "status" :value "default"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "light"}]
        dist2 (ctv/properties-distance target props2)
        dist3 (ctv/properties-distance target props3)]
    (t/is (< dist3 dist2))))

(t/deftest properties-distance02
  ;;c1: primary, default, rounded, blue, dark
  ;;c2: primary, hover, squared, red, dark
  ;;c3: secondary, hover, rounded, blue, dark

  ;; I have a copy of c1, and I change from default to hover
  ;; c2: 2 differences in pos 3 and 4
  ;; c3: 1 differences in pos 1
  ;; The min distance should be c2

  (let [target [{:name "type" :value "primary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        props2 [{:name "type" :value "primary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "squared"}
                {:name "color" :value "red"}
                {:name "theme" :value "dark"}]
        props3 [{:name "type" :value "secondary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        dist2 (ctv/properties-distance target props2)
        dist3 (ctv/properties-distance target props3)]
    (t/is (< dist2 dist3))))

(t/deftest properties-distance03
  ;;c1: primary, default, rounded, blue, dark
  ;;c2: secondary, default, rounded, blue, light
  ;;c3: secondary, hover, squared, blue, dark
  ;;c4: secondary, hover, rounded, blue, dark

  ;; I have a copy of c1, and I change from primary to secondary
  ;; c2: 1 difference in pos 4
  ;; c3: 2 differences in pos 1 and 2
  ;; c4: 1 difference in pos 1
  ;; The distances should be c2 < c4 < c3

  (let [target [{:name "type" :value "secondary"}
                {:name "status" :value "default"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        props2 [{:name "type" :value "secondary"}
                {:name "status" :value "default"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "light"}]
        props3 [{:name "type" :value "secondary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "squared"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        props4 [{:name "type" :value "secondary"}
                {:name "status" :value "hover"}
                {:name "borders" :value "rounded"}
                {:name "color" :value "blue"}
                {:name "theme" :value "dark"}]
        dist2 (ctv/properties-distance target props2)
        dist3 (ctv/properties-distance target props3)
        dist4 (ctv/properties-distance target props4)]
    (t/is (< dist2 dist4))
    (t/is (< dist4 dist3))))

(t/deftest properties-distance04
  (t/is (= 0 (ctv/properties-distance [] [])))
  (t/is (= 0 (ctv/properties-distance nil nil)))
  (t/is (= 0 (ctv/properties-distance [{:name "a" :value "x"}] [{:name "a" :value "x"}])))
  (t/is (= 2.0 (ctv/properties-distance [{:name "a" :value "x"} {:name "b" :value "y"}] [{:name "a" :value "x"} {:name "b" :value "z"}]))))




