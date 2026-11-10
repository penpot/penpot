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
   escaped too.

   Tabs and line breaks become spaces: that is what the XML parser does
   with them on the next read, and it keeps the output stable."
  [s]
  (-> (str s)
      (str/replace #"[\t\r\n]" " ")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn- emit-element
  "Serialize a parsed element, escaping every text and attribute value.

   The tag and attribute names come from the parser, so they are valid
   XML names; only their values can carry markup.

   The attributes are sorted by name: the parser hands them over in the
   reverse order of the document, so writing them as they come would
   make the output change on every sanitization."
  [{:keys [tag attrs content]}]
  (let [attrs-str   (apply str
                           (for [[k v] (sort-by (comp name key) attrs)]
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

(def ^:private url-ignored-chars
  "Characters that the URL parser discards before it interprets the
   scheme of an URL."
  #"[\u0000-\u0020\u007f]")

(defn- javascript-url?
  "True when the value is a javascript: URL.

   The comparison drops the characters the URL parser drops (tab, CR, LF
   and spaces), so a scheme split with one of them (`java&#x09;script:`)
   cannot slip through."
  [v]
  (let [v (str/replace (str v) url-ignored-chars "")]
    (boolean (re-find javascript-href-pattern (str/trim v)))))

(def ^:private dangerous-tags
  #{"script" "foreignObject" "set"
    "animate" "animateTransform" "animateColor" "animateMotion"
    "iframe" "meta" "embed" "link" "base"})

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

(def ^:private svg-namespace "http://www.w3.org/2000/svg")

(defn- namespace-bindings
  "Return the namespace bindings in scope for an element: `::default`
   plus the keyword prefixes declared in `attrs`, merged over the
   inherited ones. The default binding is stored under a namespaced
   keyword because a prefix cannot contain `/`, so no prefixed binding
   can produce the same key.

   The XML parser does not process namespaces, so the declarations are
   plain attributes (`:xmlns`, `:xmlns:svg`) and the prefix is part of
   the tag name (`:svg:svg`)."
  [attrs inherited]
  (let [own (reduce-kv (fn [acc k v]
                         (let [n (name k)]
                           (cond
                             (= n "xmlns") (assoc acc ::default v)
                             (str/starts-with? n "xmlns:") (assoc acc (keyword (subs n 6)) v)
                             :else acc)))
                       {}
                       attrs)]
    (merge inherited own)))

(defn- element-namespace
  "Return the namespace URI of `tag` under `bindings`: the default
   namespace when the tag has no prefix, the prefixed binding otherwise.

   The prefix is part of the tag name (`:svg:svg`), so the binding key
   is the prefix."
  [tag bindings]
  (let [n   (name tag)
        idx (str/last-index-of n ":")]
    (if (nil? idx)
      (::default bindings)
      (get bindings (keyword (subs n 0 idx))))))

(defn- svg-root?
  "True when the element is an `svg` element in the SVG namespace.

   The namespace is resolved with the same model as the recursion
   (`namespace-bindings` + `element-namespace`), so the check does not
   introduce a second rule."
  [{:keys [tag attrs]}]
  (let [bindings (namespace-bindings attrs {})]
    (and (= "svg" (local-name tag))
         (= svg-namespace (element-namespace tag bindings)))))

(defn- sanitize-svg-element
  "Recursively sanitize an SVG element by removing dangerous tags and
   attributes.

   The namespace bindings are tracked through the recursion: an element
   that is not in the SVG namespace is dropped with its whole subtree,
   because the filter only knows about SVG elements and a child that
   rebinds the namespace to XHTML can carry live HTML."
  [element bindings]
  (let [{:keys [tag attrs content]} element
        bindings (namespace-bindings attrs bindings)]
    (when (and (map? element) tag
               (not (contains? dangerous-tags (local-name tag)))
               (= svg-namespace (element-namespace tag bindings)))
      (let [clean-attrs  (->> attrs
                              (remove (fn [[k v]]
                                        (let [n (name k)]
                                          (or (and (not (str/starts-with? n "xmlns"))
                                                   (re-matches dangerous-attrs-pattern (local-name k)))
                                              (and (= "href" (local-name k))
                                                   (string? v)
                                                   (javascript-url? v))))))
                              (into {}))
            clean-content (when content
                            (->> content
                                 (filter #(or (string? %) (map? %)))
                                 (map (fn [child]
                                        (if (map? child)
                                          (sanitize-svg-element child bindings)
                                          child)))
                                 (filter some?)
                                 vec))]
        (cond-> {:tag tag :attrs clean-attrs}
          (seq clean-content) (assoc :content clean-content))))))

(defn sanitize-svg
  "Sanitize SVG content by removing dangerous elements and attributes.
   Removes <script> tags, <foreignObject> elements, event handlers (on*),
   and javascript: URLs from href attributes.

   The document must be an SVG document: any element that is not in the
   SVG namespace is dropped with its subtree, so a root that is not an
   `svg` element in the SVG namespace is rejected, because the filter
   only knows about SVG elements and attributes.

   The filtered tree is written back with an escaping serializer: a
   payload hidden in an attribute value or in a text node would become
   live markup again if the values were written verbatim."
  [svg-text]
  (let [parsed (try
                 (parse-svg svg-text)
                 (catch Exception e
                   (l/warn :hint "SVG parsing failed, rejecting upload" :cause e)
                   (ex/raise :type :validation
                             :code :invalid-svg-file
                             :hint "SVG parsing failed during sanitization"
                             :cause e)))
        _      (when-not (svg-root? parsed)
                 (l/warn :hint "SVG sanitization rejected a document that is not an svg document")
                 (ex/raise :type :validation
                           :code :invalid-svg-file
                           :hint "the uploaded document is not an svg document"))
        sanitized (sanitize-svg-element parsed {})]

    (when-not sanitized
      (l/warn :hint "SVG sanitization produced no output, rejecting upload")
      (ex/raise :type :validation
                :code :invalid-svg-file
                :hint "SVG sanitization produced no output"))

    (str xml-declaration (emit-element sanitized))))

(defn sanitize-svg-upload
  "Return the upload untouched when its mtype is not `image/svg+xml`.
   When it is, write a sanitized copy to a temporary file and return the
   upload pointing to it, with the size of the sanitized content.

   The caller must check the size of the upload before calling this: the
   whole file is read into memory to sanitize it. The size limit applies
   to the upload, not to the sanitized copy, which can be up to six times
   bigger (a single quote becomes `&apos;`).

   Every path that stores an uploaded SVG as its final object must go
   through this function, or the stored file can carry live markup. Two
   paths store raw bytes on purpose and do not: `upload-tempfile` (a
   channel between processes, not publicly accessible) and
   `upload-chunk` (the chunks of an upload; the assembled object goes
   through this function). The profile picture is not an SVG either:
   `import-profile-picture` ignores an `image/svg+xml` download."
  [{:keys [mtype path] :as upload}]
  (if (= "image/svg+xml" mtype)
    (let [content  (slurp (str path) :encoding "UTF-8")
          clean    (sanitize-svg content)
          tmp-path (tmp/tempfile :prefix "penpot-svg-" :suffix ".svg" :min-age "1h")]
      (spit (str tmp-path) clean :encoding "UTF-8")
      (assoc upload :path tmp-path :size (fs/size tmp-path)))
    upload))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SVG INFO EXTRACTION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-basic-info-from-svg
  [{:keys [tag attrs] :as data}]
  ;; The local name is enough here: this function only reads the
  ;; dimensions. Whether the document is a valid SVG is decided by
  ;; `sanitize-svg`, which rejects anything that is not an `svg` element
  ;; in the SVG namespace.
  (when (not= "svg" (local-name tag))
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
