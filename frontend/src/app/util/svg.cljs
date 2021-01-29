;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.util.svg
  (:require
   [cuerdas.core :as str]))

(defn clean-attrs
  "Transforms attributes to their react equivalent"
  [attrs]
  (letfn [(transform-key [key]
            (-> (name key)
                (str/replace ":" "-")
                (str/camel)
                (keyword)))

          (format-styles [style-str]
            (->> (str/split style-str ";")
                 (map str/trim)
                 (map #(str/split % ":"))
                 (group-by first)
                 (map (fn [[key val]]
                        (vector
                         (transform-key key)
                         (second (first val)))))
                 (into {})))

          (map-fn [[key val]]
            (cond
              (= key :class) [:className val]
              (and (= key :style) (string? val)) [key (format-styles val)]
              :else (vector (transform-key key) val)))]

    (->> attrs
         (map map-fn)
         (into {}))))
