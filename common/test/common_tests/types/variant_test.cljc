;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.variant-test
  (:require
   [app.common.types.variant :as ctv]
   [clojure.test :as t]))


(t/deftest variant-distance01
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
        dist2 (ctv/distance target props2)
        dist3 (ctv/distance target props3)]
    (t/is (< dist3 dist2))))


(t/deftest variant-distance02
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
        dist2 (ctv/distance target props2)
        dist3 (ctv/distance target props3)]
    (t/is (< dist2 dist3))))

(t/deftest variant-distance03
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
        dist2 (ctv/distance target props2)
        dist3 (ctv/distance target props3)
        dist4 (ctv/distance target props4)]
    (t/is (< dist2 dist4))
    (t/is (< dist4 dist3))))

(def ^:private long-text (apply str (repeat 60 "n")))

(defn- numbered-long-text
  [n]
  (let [suffix (str " (" n ")")]
    (str (subs long-text 0 (- 60 (count suffix))) suffix)))

(t/deftest clip-property-text
  (t/is (= "abc" (ctv/clip-property-text "abc" 5)))
  (t/is (= "abc" (ctv/clip-property-text "abcdef" 3))))

(t/deftest unique-property-text
  (t/testing "free text is kept"
    (t/is (= "Size" (ctv/unique-property-text [] "Size")))
    (t/is (= "A (1)" (ctv/unique-property-text ["A"] "A (1)"))))

  (t/testing "taken text gets a number"
    (t/is (= "Size (1)" (ctv/unique-property-text ["Size"] "Size")))
    (t/is (= "A (2)" (ctv/unique-property-text ["A" "A (1)"] "A (1)"))))

  (t/testing "empty text is numbered without a leading space"
    (t/is (= "(1)" (ctv/unique-property-text [""] "")))
    (t/is (= "(2)" (ctv/unique-property-text ["" "(1)"] ""))))

  (t/testing "long text is clipped"
    (t/is (= long-text (ctv/unique-property-text [] (str long-text "n")))))

  (t/testing "the base leaves room for the suffix"
    (t/is (= (numbered-long-text 1) (ctv/unique-property-text [long-text] long-text)))
    (let [taken  (into [long-text] (map numbered-long-text) (range 1 11))
          result (ctv/unique-property-text taken long-text)]
      (t/is (= (str (apply str (repeat 55 "n")) " (11)") result))
      (t/is (= 60 (count result)))
      (t/is (= result (ctv/unique-property-text taken result)))
      (t/is (= result (ctv/unique-property-text (remove #{result} taken) result)))))

  (t/testing "a numbered text over the limit keeps its suffix"
    (let [over-1  (str (subs long-text 0 57) " (1)")
          over-12 (str (subs long-text 0 58) " (12)")]
      (t/is (= (numbered-long-text 1) (ctv/unique-property-text [] over-1)))
      (t/is (= (numbered-long-text 12) (ctv/unique-property-text [] over-12)))
      (t/is (= (numbered-long-text 1)
               (ctv/unique-property-text [] (numbered-long-text 1))))
      (t/is (= (numbered-long-text 2)
               (ctv/unique-property-text [(numbered-long-text 1)] over-1))))))

(t/deftest sanitize-property-name
  (t/is (= "SizeBig" (ctv/sanitize-property-name "Size=Big" [])))
  (t/is (= "Size (1)" (ctv/sanitize-property-name "Size" ["Size"])))
  (t/is (= "Property 1" (ctv/sanitize-property-name "=,=" [])))
  (t/is (= "Property 2" (ctv/sanitize-property-name "=,=" ["Color"])))
  (t/is (= "Property 1" (ctv/sanitize-property-name 5 []))))

(t/deftest sanitize-property-value
  (t/is (= "ab" (ctv/sanitize-property-value "a=b,")))
  (t/is (= "x" (ctv/sanitize-property-value " x ")))
  (t/is (= "" (ctv/sanitize-property-value 5)))
  (t/is (= "" (ctv/sanitize-property-value nil)))
  (t/is (= long-text (ctv/sanitize-property-value (str long-text "n")))))

(t/deftest sanitize-properties
  (t/testing "other keys are kept"
    (t/is (= [{:name "SizeBig" :value "ab" :hidden true}]
             (ctv/sanitize-properties [{:name "Size=Big" :value "a=b," :hidden true}]))))

  (t/testing "names are unique"
    (t/is (= ["SizeBig" "SizeBig (1)"]
             (mapv :name (ctv/sanitize-properties [{:name "Size=Big" :value "one"}
                                                   {:name "SizeBig" :value "two"}])))))

  (t/testing "long names stay distinct and keep their values"
    (t/is (= [{:name long-text :value "x"}
              {:name (numbered-long-text 1) :value "y"}]
             (ctv/sanitize-properties [{:name long-text :value "x"}
                                       {:name long-text :value "y"}]))))

  (t/testing "a name numbered past the limit keeps its suffix"
    (let [base (subs long-text 0 57)]
      (t/is (= [base (numbered-long-text 1)]
               (mapv :name (ctv/sanitize-properties [{:name base :value "x"}
                                                     {:name (str base " (1)") :value "y"}])))))))

(t/deftest properties-names
  (t/is (= ["A" "B" "C"]
           (ctv/properties-names [[{:name "A"} {:name "B"}]
                                  [{:name "B"} {:name "C"}]]))))

(t/deftest align-properties
  (t/is (= [{:name "A" :value ""} {:name "B" :value "b" :hidden true}]
           (ctv/align-properties [{:name "B" :value "b" :hidden true}] ["A" "B"]))))

(t/deftest unique-values-tuple
  (t/testing "no clash"
    (t/is (= [{:name "S" :value "a"}]
             (ctv/unique-values-tuple [{:name "S" :value "a"}] [["b"]])))
    (t/is (= [] (ctv/unique-values-tuple [] [[]]))))

  (t/testing "the last non-blank value is numbered"
    (t/is (= [{:name "S" :value "a (1)"}]
             (ctv/unique-values-tuple [{:name "S" :value "a"}] [["a"]])))
    (t/is (= [{:name "S" :value "x"} {:name "C" :value "red (2)"} {:name "D" :value ""}]
             (ctv/unique-values-tuple [{:name "S" :value "x"} {:name "C" :value "red"} {:name "D" :value ""}]
                                      [["x" "red" ""] ["y" "red (1)" ""]]))))

  (t/testing "all values blank"
    (t/is (= [{:name "S" :value "(1)"}]
             (ctv/unique-values-tuple [{:name "S" :value ""}] [[""]])))))

(t/deftest sanitize-properties-set
  (t/testing "a colliding value tuple is numbered"
    (t/is (= [[{:name "Size" :value "ab"}]
              [{:name "Size" :value "ab (1)"}]]
             (ctv/sanitize-properties-set [[{:name "Size" :value "a=b"}]
                                           [{:name "Size" :value "a=b"}]]))))

  (t/testing "every vector gets the same names"
    (t/is (= [[{:name "SizeBig" :value "one"} {:name "SizeBig (1)" :value "two"}]
              [{:name "SizeBig" :value "only"} {:name "SizeBig (1)" :value ""}]]
             (ctv/sanitize-properties-set [[{:name "Size=Big" :value "one"}
                                            {:name "SizeBig" :value "two"}]
                                           [{:name "Size=Big" :value "only"}]]))))

  (t/testing "a clean set is unchanged and a second run changes nothing"
    (let [props-seq [[{:name "Size" :value "big"} {:name "Color" :value "red"}]
                     [{:name "Size" :value "small"} {:name "Color" :value "red"}]]
          dirty     [[{:name "a=b" :value long-text} {:name "a=b" :value ""}]
                     [{:name "ab" :value long-text}]
                     [{:name "=" :value 5}]]
          once      (ctv/sanitize-properties-set dirty)]
      (t/is (= props-seq (ctv/sanitize-properties-set props-seq)))
      (t/is (= once (ctv/sanitize-properties-set once))))))
