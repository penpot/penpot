;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.media.svg
  "SVG parsing, sanitization, and info extraction.
   Centralizes all SVG-related security concerns."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [clojure.xml :as xml]
   [cuerdas.core :as str])
  (:import
   clojure.lang.XMLHandler
   java.io.InputStream
   javax.xml.parsers.SAXParserFactory
   javax.xml.XMLConstants
   org.apache.commons.io.IOUtils))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG PARSING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- secure-parser-factory
  [^InputStream input ^XMLHandler handler]
  (.. (doto (SAXParserFactory/newInstance)
        (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
        (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true))
      (newSAXParser)
      (parse input handler)))

(defn- strip-doctype
  [data]
  (cond-> data
    (str/includes? data "<!DOCTYPE")
    (str/replace #"<\!DOCTYPE[^>]*>" "")))

(defn parse-svg
  [text]
  (let [text (strip-doctype text)]
    (dm/with-open [istream (IOUtils/toInputStream ^String text "UTF-8")]
      (xml/parse istream secure-parser-factory))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG SANITIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private dangerous-attrs-pattern #"(?i)^on\w+$")
(def ^:private javascript-href-pattern #"(?i)^javascript:")

(defn- sanitize-svg-element
  "Recursively sanitize an SVG element by removing dangerous tags and attributes."
  [{:keys [tag attrs content] :as element}]
  (when (and (map? element) tag)
    (let [dangerous-tags #{:script :foreignObject :set :animate :animateTransform :animateColor :animateMotion}]
      (when-not (contains? dangerous-tags tag)
        (let [clean-attrs (->> attrs
                               (remove (fn [[k v]]
                                         (or (re-matches dangerous-attrs-pattern (name k))
                                             (and (#{:href :xlink:href} k)
                                                  (string? v)
                                                  (re-find javascript-href-pattern (str/trim v))))))
                               (into {}))
              clean-content (when content
                              (->> content
                                   (filter #(or (string? %) (map? %)))
                                   (map (fn [child]
                                          (if (map? child)
                                            (sanitize-svg-element child)
                                            child)))
                                   (filter some?)
                                   vec))]
          (cond-> {:tag tag :attrs clean-attrs}
            (seq clean-content) (assoc :content clean-content)))))))

(defn sanitize-svg
  "Sanitize SVG content by removing dangerous elements and attributes.
   Removes <script> tags, <foreignObject> elements, event handlers (on*),
   and javascript: URLs from href attributes."
  [svg-text]
  (try
    (let [parsed (parse-svg svg-text)
          sanitized (sanitize-svg-element parsed)]
      (if sanitized
        (with-out-str (xml/emit sanitized))
        (ex/raise :type :validation
                  :code :invalid-svg-file
                  :hint "SVG sanitization produced no output")))
    (catch Exception e
      (l/warn :hint "SVG sanitization failed, rejecting upload" :cause e)
      (ex/raise :type :validation
                :code :invalid-svg-file
                :hint "SVG parsing failed during sanitization"
                :cause e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG INFO EXTRACTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-basic-info-from-svg
  [{:keys [tag attrs] :as data}]
  (when (not= tag :svg)
    (ex/raise :type :validation
              :code :unable-to-parse-svg
              :hint "uploaded svg has invalid content"))
  (reduce (fn [default f]
            (if-let [res (f attrs)]
              (reduced res)
              default))
          {:width 100 :height 100}
          [(fn parse-width-and-height
             [{:keys [width height]}]
             (when (and (string? width)
                        (string? height))
               (let [width  (d/parse-double width)
                     height (d/parse-double height)]
                 (when (and width height)
                   {:width (int width)
                    :height (int height)}))))
           (fn parse-viewbox
             [{:keys [viewBox]}]
             (let [[x y width height] (->> (str/split viewBox #"\s+" 4)
                                           (map d/parse-double))]
               (when (and x y width height)
                 {:width (int width)
                  :height (int height)})))]))
