;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.data.object-diff-generators
  "Value generators and shape predicates shared by the object-diff tests."
  (:require
   [clojure.test.check.generators :as tg]))

(def gen-key
  (tg/one-of [(tg/return :a) (tg/return :b) (tg/return "a")
              (tg/return 1) (tg/return nil)]))

(def gen-scalar
  (tg/one-of [(tg/return nil) tg/boolean (tg/choose 0 3)
              (tg/return :x) (tg/return "a")]))

(def gen-value
  (tg/recursive-gen
   (fn [gen]
     (tg/one-of [(tg/vector gen)
                 (tg/set gen {:max-elements 3})
                 (tg/map gen-key gen {:max-elements 3})]))
   gen-scalar))

(defn valid-op?
  [op]
  (and (vector? op)
       (vector? (second op))
       (case (first op)
         :set (= 3 (count op))
         :add (= 3 (count op))
         :del (= 2 (count op))
         false)))

(defn valid-element?
  "A diff element is either an operation or a group that holds at least
  one operation and a vector path."
  [element]
  (if (map? element)
    (let [sets  (:set element)
          adds  (:add element)
          dels  (:del element)
          pair? (fn [pair] (and (vector? pair) (= 2 (count pair))))]
      (and (vector? (:path element))
           (pos? (+ (count sets) (count adds) (count dels)))
           (every? pair? sets)
           (every? pair? adds)
           (or (nil? dels) (vector? dels))))
    (valid-op? element)))
