;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns app.util.svg
  "Icons SVG parsing helpers."
  (:require
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [app.common.spec :as us]
   [app.common.exceptions :as ex])
  (:import
   org.jsoup.Jsoup
   org.jsoup.nodes.Attribute
   org.jsoup.nodes.Element
   org.jsoup.nodes.Document
   java.io.InputStream))

(s/def ::content string?)
(s/def ::width ::us/number)
(s/def ::height ::us/number)
(s/def ::name string?)
(s/def ::view-box (s/coll-of ::us/number :min-count 4 :max-count 4))

(s/def ::svg-entity
  (s/keys :req-un [::content ::width ::height ::view-box]
          :opt-un [::name]))

;; --- Implementation

(defn- parse-double
  [data]
  (s/assert ::us/string data)
  (Double/parseDouble data))

(defn- parse-viewbox
  [data]
  (s/assert ::us/string data)
  (mapv parse-double (str/split data #"\s+")))

(defn- parse-attrs
  [^Element element]
  (persistent!
   (reduce (fn [acc ^Attribute attr]
             (let [key (.getKey attr)
                   val (.getValue attr)]
               (case key
                 "width" (assoc! acc :width (parse-double val))
                 "height" (assoc! acc :height (parse-double val))
                 "viewbox" (assoc! acc :view-box (parse-viewbox val))
                 "sodipodi:docname" (assoc! acc :name val)
                 acc)))
           (transient {})
           (.attributes element))))

(defn- impl-parse
  [data]
  (try
    (let [document (Jsoup/parse ^String data)
          element (some-> (.body ^Document document)
                          (.getElementsByTag "svg")
                          (first))
          content (.html element)
          attrs (parse-attrs element)]
      (assoc attrs :content content))
    (catch java.lang.IllegalArgumentException e
      (ex/raise :type :validation
                :code ::invalid-input
                :message "Input does not seems to be a valid svg."))
    (catch java.lang.NullPointerException e
      (ex/raise :type :validation
                :code ::invalid-input
                :message "Input does not seems to be a valid svg."))
    (catch org.jsoup.UncheckedIOException e
      (ex/raise :type :validation
                :code ::invalid-input
                :message "Input does not seems to be a valid svg."))
    (catch Exception e
      (ex/raise :type :internal
                :code ::unexpected))))

;; --- Public Api

(defn parse-string
  "Parse SVG from a string."
  [data]
  (s/assert ::us/string data)
  (let [result (impl-parse data)]
    (if (s/valid? ::svg-entity result)
      result
      (ex/raise :type :validation
                :code ::invalid-result
                :message "The result does not conform valid svg entity."))))

(defn parse
  [data]
  (parse-string (slurp data)))
