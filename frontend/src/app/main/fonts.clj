;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.fonts
  "A fonts loading macros."
  (:require
   [app.common.uuid :as uuid]
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [cuerdas.core :as str]))

(defn- parse-gfont-variant
  [variant files]
  (cond
    (= "regular" variant)
    {:id "regular" :name "regular" :weight "400" :style "normal" :ttf-url (get files "regular")}

    (= "italic" variant)
    {:id "italic" :name "italic" :weight "400" :style "italic" :ttf-url (get files "italic")}

    :else
    (when-let [[id weight style] (re-find #"^(\d+)(.*)$" variant)]
      {:id id
       :name variant
       :weight weight
       :style (if (str/empty? style) "normal" style)
       :ttf-url (get files id)})))

(defn- parse-gfont
  [font]
  (let [family (get font "family")
        variants (get font "variants")
        files (get font "files")]
    {:id (str "gfont-" (str/slug family))
     :uuid (uuid/random)
     :family family
     :name family
     :variants (into [] (comp (map (fn [variant] (parse-gfont-variant variant files)))
                              (filter identity))
                     variants)}))

(defmacro preload-gfonts
  [path]
  (let [data (slurp (io/resource path))
        data (json/read-str data)]
    `~(mapv parse-gfont (get data "items"))))



