;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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




