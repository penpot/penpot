;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.simple-math
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [cljs.spec.alpha :as s]
   [clojure.string :refer [index-of]]
   [cuerdas.core :as str]
   [instaparse.core :as insta]))

(def parser
  (insta/parser
    "opt-expr = '' | expr
     expr = term (<spaces> ('+'|'-') <spaces> expr)* |
            ('+'|'-'|'*'|'/') <spaces> factor
     term = factor (<spaces> ('*'|'/') <spaces> term)*
     factor = number | ('(' <spaces> expr <spaces> ')')
     number = #'[0-9]*[.,]?[0-9]+%?'
     spaces = ' '*"))

(defn interpret
  [tree init-value]
  (let [token (first tree)
        args  (rest tree)]
    (case token
      :opt-expr
      (if (empty? args) nil (interpret (first args) init-value))

      :expr
      (if (index-of "+-*/" (first args))
        (let [operator     (first args)
              second-value (interpret (second args) init-value)]
          (case operator
            "+" (+ init-value second-value)
            "-" (- 0 second-value)          ;; Note that there is ambiguity, so we don't allow
            "*" (* init-value second-value) ;; relative substraction, it's only a negative number
            "/" (/ init-value second-value)))
        (let [value (interpret (first args) init-value)]
          (loop [value     value
                 rest-expr (rest args)]
            (if (empty? rest-expr)
              value
              (let [operator     (first rest-expr)
                    second-value (interpret (second rest-expr) init-value)
                    rest-expr    (-> rest-expr rest rest)]
                (case operator
                  "+" (recur (+ value second-value) rest-expr)
                  "-" (recur (- value second-value) rest-expr)))))))

      :term
      (let [value (interpret (first args) init-value)]
        (loop [value     value
               rest-expr (rest args)]
          (if (empty? rest-expr)
            value
            (let [operator     (first rest-expr)
                  second-value (interpret (second rest-expr) init-value)
                  rest-expr    (-> rest-expr rest rest)]
              (case operator
                "*" (recur (* value second-value) rest-expr)
                "/" (recur (/ value second-value) rest-expr))))))

      :factor
      (if (= (first args) "(")
        (interpret (second args) init-value)
        (interpret (first args) init-value))

      :number
      (let [value-str (str/replace (first args) "," ".")]
        (if-not (str/ends-with? value-str "%")
          (d/parse-double value-str)
          (-> value-str
              (str/replace "%" "")
              (d/parse-double)
              (/ 100)
              (* init-value))))

      (ex/raise :type :validation
                :hint (str "Unknown token" token args)))))

(defn expr-eval
  [expr init-value]
  (s/assert string? expr)
  (let [result     (parser expr)
        init-value (or init-value 0)]
    (s/assert number? init-value)
    (if-not (insta/failure? result)
      (interpret result init-value)
      (let [text (:text result)
            index (:index result)
            expecting (->> result
                           :reason
                           (map :expecting)
                           (filter some?))]
        (js/console.debug
          (str "Invalid value '" text "' at index " index
               ". Expected one of " expecting "."))
        nil))))

