;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.strings
  (:require
   [cuerdas.core :as str]))

(def ^:const trail-zeros-regex-1 #"\.0+$")
(def ^:const trail-zeros-regex-2 #"(\.\d*[^0])0+$")

(defn format-precision
  "Creates a number with predetermined precision and then removes the trailing 0.
  Examples:
    12.0123, 0 => 12
    12.0123, 1 => 12
    12.0123, 2 => 12.01"
  [num precision]

  (try
    (if (number? num)
      (let [num-str (.toFixed num precision)

            ;; Remove all trailing zeros after the comma 100.00000
            num-str (str/replace num-str trail-zeros-regex-1 "")

            ;; Remove trailing zeros after a decimal number: 0.001|00|
            num-str (if-let [m (re-find trail-zeros-regex-2 num-str)]
                      (str/replace num-str (first m) (second m))
                      num-str)]
        num-str)
      (str num))
    (catch :default _
      (str num))))

(defn matches-search
  [name search-term]
  (if (str/empty? search-term)
    true
    (let [st (str/trim (str/lower search-term))
          nm (str/trim (str/lower name))]
      (str/includes? nm st))))

(defn camelize
  [str]
  ;; str.replace(":", "-").replace(/-./g, x=>x[1].toUpperCase())
  (when (not (nil? str))
    (js* "~{}.replace(\":\", \"-\").replace(/-./g, x=>x[1].toUpperCase())", str)))
