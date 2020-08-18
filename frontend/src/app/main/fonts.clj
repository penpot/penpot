;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.fonts
  "A fonts loading macros."
  (:require
   [cuerdas.core :as str]
   [clojure.java.io :as io]
   [clojure.data.json :as json]))

(defn- parse-gfont-variant
  [variant]
  (cond
    (= "regular" variant)
    {:name "regular" :weight "400" :style "normal"}

    (= "italic" variant)
    {:name "italic" :weight "400" :style "italic"}

    :else
    (when-let [[a b c] (re-find #"^(\d+)(.*)$" variant)]
      (if (str/empty? c)
        {:id a :name b :weight b :style "normal"}
        {:id a :name (str b " (" c ")") :weight b :style c}))))

(defn- parse-gfont
  [font]
  (let [family (get font "family")
        variants (get font "variants")]
    {:id (str "gfont-" (str/slug family))
     :family family
     :name family
     :variants (into [] (comp (map parse-gfont-variant)
                              (filter identity))
                     variants)}))

(defmacro preload-gfonts
  [path]
  (let [data (slurp (io/resource path))
        data (json/read-str data)]
    `~(mapv parse-gfont (get data "items"))))



