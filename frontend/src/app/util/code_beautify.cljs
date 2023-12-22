;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-beautify
  (:require
   ["js-beautify" :as beautify]
   [cuerdas.core :as str]))

(defn format-html
  [data]
  (beautify/html data #js {:indent_size 2}))

(defn format-code
  [code type]
  (let [type (if (keyword? type) (name type) type)]
    (cond-> code
      (= type "svg")
      (-> (str/replace "<defs></defs>" "")
          (str/replace "><" ">\n<"))

      (or (= type "svg") (= type "html"))
      (format-html))))

