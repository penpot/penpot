;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.media.validation
  "Schemas and validation functions for media uploads.
   Leaf namespace — depends on app.common.* and app.config only."
  (:require
   [app.common.exceptions :as ex]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.config :as cf]
   [cuerdas.core :as str]
   [datoteka.fs :as fs])
  (:import
   com.j256.simplemagic.ContentInfo
   com.j256.simplemagic.ContentInfoUtil
   java.nio.file.Path))

(def schema:upload
  [:map {:title "Upload"}
   [:filename :string]
   [:size ::sm/int]
   [:path ::fs/path]
   [:mtype {:optional true} :string]
   [:headers {:optional true}
    [:map-of :string :string]]])

(def schema:input
  [:map {:title "Input"}
   [:path ::fs/path]
   [:mtype {:optional true} ::sm/text]])

(def check-input
  (sm/check-fn schema:input))

(def allowed-upload-prefixes
  ;; Upload temp files are created server-side by the multipart parser
  ;; (yetti `default-temp-dir`, `/tmp/undertow/`) plus the tmp dir used
  ;; by tests and storage tmp files (`/tmp/penpot/`). Anything else
  ;; (e.g. `/etc/passwd` smuggled via Transit `~#path`) is rejected.
  ["/tmp/undertow/" "/tmp/penpot/"])

(defn check-upload-path
  [upload]
  (let [real (try (str (fs/real (:path upload)))
                  (catch Exception _ nil))]
    (when (or (nil? real)
              (not (some #(str/starts-with? real %) allowed-upload-prefixes)))
      (ex/raise :type :validation
                :code :params-validation
                :hint "invalid upload path"))
    upload))

(def ^:private ^ContentInfoUtil magic-util
  "Shared magic-byte detector (thread-safe for matching, read-only
  after construction). Built once: parsing the embedded magic database
  on every upload would be wasteful."
  (ContentInfoUtil.))

(defn- normalize-mtype
  "Lowercase a mime string and drop any `;` parameters. Returns nil
  for missing or blank values."
  [mtype]
  (when (string? mtype)
    (let [clean (-> (str/split mtype #";" 2)
                    (first)
                    (str/trim)
                    (str/lower))]
      (when-not (str/empty? clean)
        clean))))

(def ^:private simplemagic-aliases
  "Spellings reported by `ContentInfoUtil` that differ from the Penpot
  vocabulary in `app.common.media`. Single place to extend when the
  magic database gains new spellings."
  {"image/x-png"                "image/png"
   "image/x-jpeg"               "image/jpeg"
   "image/jpg"                  "image/jpeg"
   "application/x-font-ttf"     "font/ttf"
   "application/x-font-otf"     "font/otf"
   "application/font-sfnt"      "font/ttf"
   "application/x-font-woff"    "font/woff"
   "application/font-woff"      "font/woff"
   "application/x-font-woff2"   "font/woff2"
   "application/font-woff2"     "font/woff2"})

(def ^:private magic-name->mtype
  "The magic database knows TrueType and OpenType by name but reports
  an empty mime for them. Map those names to the Penpot vocabulary."
  {"TrueType" "font/ttf"
   "OpenType" "font/otf"})

(defn- read-four-cc
  "Reads the FourCC signature of the file at `path` — its first four
  bytes as an ASCII string. Used only for the font containers the
  magic database does not know (WOFF/WOFF2). Returns nil on any read
  failure."
  [^Path path]
  (try
    (with-open [in (java.io.FileInputStream. (str path))]
      (let [buf (byte-array 4)
            n   (.read in buf)]
        (when (= n 4)
          (String. ^bytes buf "US-ASCII"))))
    (catch Exception _ nil)))

(def ^:private four-cc->mtype
  {"wOFF" "font/woff"
   "wOF2" "font/woff2"})

(def ^:private svg-probe-bytes
  "How many leading bytes the SVG root probe reads. The root element
  of a real document sits at the top; 64KB leaves room for a long
  prolog or comments while keeping memory constant."
  65536)

(defn- starts-at?
  "True when `prefix` occurs in `s` exactly at `i`."
  [^String s ^long i ^String prefix]
  (let [plen (.length prefix)]
    (and (<= (+ i plen) (.length s))
         (.regionMatches s (int i) prefix (int 0) (int plen)))))

(defn- skip-ws
  "Index of the first non-whitespace char in `s` at or after `i`."
  [^String s ^long i]
  (let [n (.length s)]
    (loop [i i]
      (if (and (< i n) (Character/isWhitespace (.charAt s i)))
        (recur (inc i))
        i))))

(defn- index-of-from
  "Index of the first occurrence of `end` in `s` at or after `i`, or
  nil when absent."
  [^String s ^long i ^String end]
  (loop [i i]
    (cond
      (> i (- (.length s) (.length end))) nil
      (starts-at? s i end) i
      :else (recur (inc i)))))

(defn- skip-markup-decl
  "Index just past a `<!...>` declaration opening at `i` (which points
  at `<`), honouring `[...]` nesting so a doctype internal subset
  cannot fool it. Returns nil when unterminated."
  [^String s ^long i]
  (let [n (.length s)]
    (loop [i (+ i 2) depth 0]
      (when (< i n)
        (let [c (.charAt s i)]
          (cond
            (= c \[) (recur (inc i) (inc depth))
            (= c \]) (recur (inc i) (dec depth))
            (and (= c \>) (zero? depth)) (inc i)
            :else (recur (inc i) depth)))))))

(defn- svg-root-at?
  "True when the first markup in `s` is a lowercase `<svg` root
  element, skipping the XML prolog, comments and doctype. Anything
  else — including a truncated head — is false; full validity stays
  downstream with the SVG parser."
  [^String s]
  (loop [i (skip-ws s 0)]
    (when (< i (.length s))
      (cond
        (starts-at? s i "<?")
        (when-some [j (index-of-from s (+ i 2) "?>")]
          (recur (skip-ws s (+ j 2))))

        (starts-at? s i "<!--")
        (when-some [j (index-of-from s (+ i 4) "-->")]
          (recur (skip-ws s (+ j 3))))

        (starts-at? s i "<!")
        (when-some [j (skip-markup-decl s i)]
          (recur (skip-ws s j)))

        (starts-at? s i "<svg")
        (let [j (+ i 4)]
          (boolean
           (when (< j (.length s))
             (contains? #{\space \tab \newline \return \/ \>} (.charAt s j)))))

        :else false))))

(defn- read-head
  "Read at most `svg-probe-bytes` leading bytes of the file at `path`,
  decoded as UTF-8 like the rest of the pipeline. Malformed sequences
  become replacement chars and can never match a tag. A leading U+FEFF
  (UTF-8 BOM) is stripped: it is an encoding artifact, not content.
  Returns nil on any read failure."
  [^Path path]
  (try
    (with-open [in (java.io.FileInputStream. (str path))]
      (let [buf (byte-array svg-probe-bytes)
            n   (.read in buf)]
        (when (pos? n)
          (let [s (String. ^bytes buf (int 0) (int n) "UTF-8")]
            (if (and (pos? (.length s)) (= \uFEFF (.charAt s 0)))
              (subs s 1)
              s)))))
    (catch Exception _ nil)))

(defn- svg-file?
  "True when the file at `path` opens with an `<svg` root element.
  This is the arbiter for SVG when magic returns nothing or a generic
  XML type. Reads at most `svg-probe-bytes` leading bytes, so memory
  stays constant no matter the file size. Full validity stays
  downstream (`parse-svg` + sanitize): this only tells SVG-looking
  text apart from other magic-less files."
  [^Path path]
  (boolean
   (when (and (some? path) (fs/exists? path))
     (some-> (read-head path) (svg-root-at?)))))

(defn detect-mtype
  "Sniff the real mime type of the file at `path` using magic bytes.
  Returns a normalized Penpot mime string, or nil when the file cannot
  be read or nothing matches."
  [^Path path]
  (try
    (when (and (some? path) (fs/exists? path))
      (let [^ContentInfo info (.findMatch magic-util (str path))]
        (if (some? info)
          (let [raw (normalize-mtype (.getMimeType info))]
            (if (some? raw)
              (get simplemagic-aliases raw raw)
              (get magic-name->mtype (.getName info))))
          (get four-cc->mtype (read-four-cc path)))))
    (catch Exception _ nil)))

(defn- svg-declared?
  [declared]
  (= declared "image/svg+xml"))

(defn- sniff-match?
  "Strict comparison of the declared client mime against the sniffed
  file content. SVG is the only exception: magic may return
  `image/svg+xml` (an SVG with an XML prolog) or a generic XML type;
  when sniffed is nil or a generic XML type, a declared `image/svg+xml`
  is accepted only when the root-tag probe confirms an `<svg` root.
  Everything else must be exactly equal — including
  `application/octet-stream`, which never matches."
  [declared sniffed path]
  (and (some? declared)
       (or (= declared sniffed)
           (and (svg-declared? declared)
                (or (nil? sniffed)
                    (contains? #{"application/xml" "text/xml"} sniffed))
                (svg-file? path)))))

(defn check-media-type
  ([upload] (check-media-type upload cm/image-types))
  ([upload allowed]
   (let [declared (normalize-mtype (:mtype upload))
         path     (:path upload)
         sniffed  (detect-mtype path)]
     ;; Declared type outside the allowlist is rejected on its face,
     ;; with the established code.
     (when-not (contains? allowed declared)
       (ex/raise :type :validation
                 :code :media-type-not-allowed
                 :hint "seems like you are uploading an invalid media object"))
     ;; Declared type allowed but file bytes differ: the client lied.
     (when-not (sniff-match? declared sniffed path)
       (ex/raise :type :validation
                 :code :media-type-mismatch
                 :hint (str/ffmt "the uploaded file content (%) does not match the declared type (%)"
                                 (or sniffed "unknown")
                                 (or (:mtype upload) "unknown")))))

   upload))

(defn- real-size
  "Bytes on disk for the file at `path`. Returns nil when the file
  cannot be read — callers run `check-upload-path` first, so a nil
  here fails closed with the path code instead of a limit code."
  [path]
  (try
    (when (some? path)
      (fs/size path))
    (catch Exception _ nil)))

(defn check-media-size
  "Rejects when the file bytes on disk exceed the configured
  `:media-max-file-size` limit. The declared `:size` is ignored —
  only the file at `:path` counts."
  [upload]
  (let [max-size  (cf/get :media-max-file-size)
        disk-size (real-size (:path upload))]
    (when (nil? disk-size)
      (ex/raise :type :validation
                :code :params-validation
                :hint "invalid upload path"))
    (when (> disk-size max-size)
      (ex/raise :type :restriction
                :code :media-max-file-size-reached
                :hint (str/ffmt "the uploaded file size % is greater than the maximum %"
                                disk-size
                                max-size)))
    upload))

(defn check-font-size
  "Rejects when the font file bytes on disk exceed the configured
  `:font-max-file-size` limit. The declared `:size` is ignored —
  only the file at `:path` counts."
  [upload]
  (let [max-size  (cf/get :font-max-file-size)
        disk-size (real-size (:path upload))]
    (when (nil? disk-size)
      (ex/raise :type :validation
                :code :params-validation
                :hint "invalid upload path"))
    (when (> disk-size max-size)
      (ex/raise :type :restriction
                :code :font-max-file-size-reached
                :hint (str/ffmt "the uploaded font size % is greater than the maximum %"
                                disk-size
                                max-size)))
    upload))
