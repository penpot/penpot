;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.services.svgparse
  (:require [clojure.spec :as s]
            [cuerdas.core :as str]
            [uxbox.util.spec :as us]
            [uxbox.services.core :as core]
            [uxbox.util.exceptions :as ex])
  (:import org.jsoup.Jsoup
           java.io.InputStream))

(s/def ::content string?)
(s/def ::width number?)
(s/def ::height number?)
(s/def ::name string?)
(s/def ::view-box (s/coll-of number? :min-count 4 :max-count 4))
(s/def ::svg-entity (s/keys :req-un [::content ::width ::height ::view-box]
                            :opt-un [::name]))

;; --- Implementation

(defn- parse-double
  [data]
  {:pre [(string? data)]}
  (Double/parseDouble data))

(defn- parse-viewbox
  [data]
  {:pre [(string? data)]}
  (mapv parse-double (str/split data #"\s+")))

(defn- assoc-attr
  [acc attr]
  (let [key (.getKey attr)
        val (.getValue attr)]
    (case key
      "width" (assoc acc :width (parse-double val))
      "height" (assoc acc :height (parse-double val))
      "viewbox" (assoc acc :view-box (parse-viewbox val))
      "sodipodi:docname" (assoc acc :name val)
      acc)))

(defn- parse-attrs
  [element]
  (let [attrs (.attributes element)]
    (reduce assoc-attr {} attrs)))

(defn- parse-svg
  [data]
  (try
    (let [document (Jsoup/parse data)
          svgelement (some-> (.body document)
                             (.getElementsByTag "svg")
                             (first))
          innerxml (.html svgelement)
          attrs (parse-attrs svgelement)]
      (merge {:content innerxml} attrs))
    (catch java.lang.IllegalArgumentException e
      (ex/raise :type :validation
                :code ::invalid-input
                :message "Input does not seems to be a valid svg."))
    (catch java.lang.NullPointerException e
      (ex/raise :type :validation
                :code ::invalid-input
                :message "Input does not seems to be a valid svg."))
    (catch Exception e
      (.printStackTrace e)
      (ex/raise :code ::unexpected))))

;; --- Public Api

(defn parse-string
  "Parse SVG from a string."
  [data]
  {:pre [(string? data)]}
  (let [result (parse-svg data)]
    (if (s/valid? ::svg-entity result)
      result
      (ex/raise :type :validation
                :code ::invalid-result
                :message "The result does not conform valid svg entity."))))

(defn parse
  [data]
  (parse-string (slurp data)))

(defmethod core/query :parse-svg
  [{:keys [data] :as params}]
  {:pre [(string? data)]}
  (parse-string data))
