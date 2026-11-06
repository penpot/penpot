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
   [app.storage.tmp :as tmp]
   [clojure.xml :as xml]
   [cuerdas.core :as str]
   [datoteka.fs :as fs])
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
;; SVG SERIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private xml-declaration
  "Declaration written at the top of every sanitized document."
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")

(defn- escape-text
  "Escape the characters that are special inside a XML text node."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- escape-attr
  "Escape the characters that are special inside a XML attribute value.
   The value is written between double quotes, so the quote itself is
   escaped too."
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- emit-element
  "Serialize a parsed element, escaping every text and attribute value.

   The tag and attribute names come from the parser, so they are valid
   XML names; only their values can carry markup."
  [{:keys [tag attrs content]}]
  (let [attrs-str   (apply str
                           (for [[k v] attrs]
                             (str " " (name k) "=\"" (escape-attr (str v)) "\"")))
        content-str (apply str
                           (for [child content]
                             (if (map? child)
                               (emit-element child)
                               (escape-text child))))]
    (if (seq content)
      (str "<" (name tag) attrs-str ">" content-str "</" (name tag) ">")
      (str "<" (name tag) attrs-str "/>"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG SANITIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private dangerous-attrs-pattern #"(?i)^on\w+$")
(def ^:private javascript-href-pattern #"(?i)^javascript:")

(def ^:private dangerous-tags
  #{"script" "foreignObject" "set"
    "animate" "animateTransform" "animateColor" "animateMotion"})

(defn- local-name
  "Return the local name of a parsed tag or attribute keyword.

   The XML parser interns qualified names with the prefix included in
   the keyword name and no keyword namespace: `<x:script>` parses as
   the keyword `:x:script`. Dangerous elements and attributes behave
   the same in the browser regardless of the prefix used, so matching
   is done on the local name: the part after the last `:`."
  [k]
  (let [n (name k)]
    (if (str/includes? n ":")
      (last (str/split n ":"))
      n)))

(defn- sanitize-svg-element
  "Recursively sanitize an SVG element by removing dangerous tags and attributes."
  [{:keys [tag attrs content] :as element}]
  (when (and (map? element) tag)
    (when-not (contains? dangerous-tags (local-name tag))
      (let [clean-attrs (->> attrs
                             (remove (fn [[k v]]
                                       (or (re-matches dangerous-attrs-pattern (local-name k))
                                           (and (= "href" (local-name k))
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
          (seq clean-content) (assoc :content clean-content))))))

(defn sanitize-svg
  "Sanitize SVG content by removing dangerous elements and attributes.
   Removes <script> tags, <foreignObject> elements, event handlers (on*),
   and javascript: URLs from href attributes.

   The filtered tree is written back with an escaping serializer: a
   payload hidden in an attribute value or in a text node would become
   live markup again if the values were written verbatim."
  [svg-text]
  (try
    (let [parsed (parse-svg svg-text)
          sanitized (sanitize-svg-element parsed)]
      (if sanitized
        (str xml-declaration (emit-element sanitized))
        (ex/raise :type :validation
                  :code :invalid-svg-file
                  :hint "SVG sanitization produced no output")))
    (catch Exception e
      (l/warn :hint "SVG sanitization failed, rejecting upload" :cause e)
      (ex/raise :type :validation
                :code :invalid-svg-file
                :hint "SVG parsing failed during sanitization"
                :cause e))))

(defn sanitize-svg-upload
  "Return the upload untouched when it does not hold an SVG. When it
   does, write a sanitized copy to a temporary file and return the
   upload pointing to it, with the size of the sanitized content.

   Every path that stores an uploaded SVG must go through this function,
   or the stored file can carry live markup."
  [{:keys [mtype path] :as upload}]
  (if (= "image/svg+xml" mtype)
    (let [content (slurp (str path) :encoding "UTF-8")
          clean   (sanitize-svg content)
          tmp     (tmp/tempfile :prefix "penpot-svg-" :suffix ".svg" :min-age "1h")]
      (spit (str tmp) clean :encoding "UTF-8")
      (assoc upload :path tmp :size (fs/size tmp)))
    upload))

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
