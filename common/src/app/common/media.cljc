;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.media
  "Media assets helpers (images, fonts, etc)"
  (:require
   [cuerdas.core :as str]))

(def font-types
  #{"font/ttf"
    "font/woff"
    "font/woff2"
    "font/otf"})

(def image-types
  #{"image/jpeg"
    "image/png"
    "image/webp"
    "image/gif"
    "image/svg+xml"})

(def tempfile-types
  (conj image-types "application/pdf" "application/zip"))

(defn format->extension
  [format]
  (case format
    :png  ".png"
    :jpeg ".jpg"
    :webp ".webp"
    :gif ".gif"
    :svg  ".svg"))

(defn format->mtype
  [format]
  (case format
    :png  "image/png"
    :jpeg "image/jpeg"
    :jpg  "image/jpeg"
    :webp "image/webp"
    :gif "image/gif"
    :svg  "image/svg+xml"
    "application/octet-stream"))

(defn mtype->format
  [mtype]
  (case mtype
    "image/png"     :png
    "image/jpeg"    :jpeg
    "image/webp"    :webp
    "image/gif"     :gif
    "image/svg+xml" :svg
    nil))

(defn mtype->extension [mtype]
  ;; https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types
  (case mtype
    "image/apng"               ".apng"
    "image/avif"               ".avif"
    "image/gif"                ".gif"
    "image/jpeg"               ".jpg"
    "image/png"                ".png"
    "image/svg+xml"            ".svg"
    "image/webp"               ".webp"
    "application/zip"          ".zip"
    "application/penpot"       ".penpot"
    "application/pdf"          ".pdf"
    "text/plain"               ".txt"
    "font/woff"                ".woff"
    "font/woff2"               ".woff2"
    "font/ttf"                 ".ttf"
    "font/otf"                 ".otf"
    "application/octet-stream" ".bin"
    nil))

(defn strip-image-extension
  [filename]
  (let [image-extensions-re #"(\.png)|(\.jpg)|(\.jpeg)|(\.webp)|(\.gif)|(\.svg)$"]
    (str/replace filename image-extensions-re "")))

(defn parse-font-weight
  [variant]
  (cond
    (re-seq #"(?i)(?:^|[-_\s])(hairline|thin)(?=(?:[-_\s]|$|italic\b))" variant)               100
    (re-seq #"(?i)(?:^|[-_\s])(extra\s*light|ultra\s*light)(?=(?:[-_\s]|$|italic\b))" variant) 200
    (re-seq #"(?i)(?:^|[-_\s])(light)(?=(?:[-_\s]|$|italic\b))" variant)                       300
    (re-seq #"(?i)(?:^|[-_\s])(normal|regular)(?=(?:[-_\s]|$|italic\b))" variant)              400
    (re-seq #"(?i)(?:^|[-_\s])(medium)(?=(?:[-_\s]|$|italic\b))" variant)                      500
    (re-seq #"(?i)(?:^|[-_\s])(semi\s*bold|demi\s*bold)(?=(?:[-_\s]|$|italic\b))" variant)     600
    (re-seq #"(?i)(?:^|[-_\s])(extra\s*bold|ultra\s*bold)(?=(?:[-_\s]|$|italic\b))" variant)   800
    (re-seq #"(?i)(?:^|[-_\s])(bold)(?=(?:[-_\s]|$|italic\b))" variant)                        700
    (re-seq #"(?i)(?:^|[-_\s])(extra\s*black|ultra\s*black)(?=(?:[-_\s]|$|italic\b))" variant) 950
    (re-seq #"(?i)(?:^|[-_\s])(black|heavy|solid)(?=(?:[-_\s]|$|italic\b))" variant)           900
    :else                                                                                      400))

(defn parse-font-style
  [variant]
  (if (or (re-seq #"(?i)(?:^|[-_\s])(italic)(?:[-_\s]|$)" variant)
          (re-seq #"(?i)italic$" variant))
    "italic"
    "normal"))

(defn parse-font-file-metadata
  "Derive font metadata from a font filename using the historical
  WOFF2 filename fallback semantics.

  Returns a map with :font-family, :font-weight and
  :font-style. Weight and style are parsed from the extension
  stripped base-name with the standard parse-font-weight and
  parse-font-style rules; no new filename rules are added here."
  [filename]
  (let [base-name       (str/replace filename #"\.[^.]+$" "")
        ;; Strip known weight/style tokens and separators to derive family name
        ;; Use word boundaries to avoid matching substrings (e.g. "Boldini" should not match "bold")
        raw-family-name (-> base-name
                            (str/replace #"(?i)(^|[-_\s])(extra\s*black|ultra\s*black|extra\s*bold|ultra\s*bold|semi\s*bold|demi\s*bold|extra\s*light|ultra\s*light|hairline|thin|light|normal|regular|medium|bold|black|heavy|solid|italic)([-_\s]|$)" "$1$3")
                            (str/replace #"[-_\s]+" " ")
                            (str/trim))
        family-name     (if (str/blank? raw-family-name) base-name raw-family-name)]
    {:font-family family-name
     :font-weight (parse-font-weight base-name)
     :font-style  (parse-font-style base-name)}))

(defn pick-font-variant
  "Return the first nonblank string from the given subfamily candidates,
  or nil when none is usable. opentype.js may return blank strings that
  plain `or` would wrongly select over a valid later candidate."
  [& candidates]
  (first (filter #(and (string? %) (not (str/blank? %))) candidates)))

(defn resolve-parsed-font-metadata
  "Resolve upload metadata for a font successfully parsed by opentype.js.
  This is the decision used by the parsed-font upload path.

  `variant` is usable only when it is a nonblank string; then weight
  and style are parsed from it and it is kept as `:variant-name`.
  Otherwise weight and style fall back to the filename metadata, the
  parsed family is preferred (filename family only when parsed family
  is unavailable, else \"\"), and `:variant-name` stays absent so the
  display label derives from weight/style."
  [parsed-family variant filename]
  (if (and (string? variant) (not (str/blank? variant)))
    {:font-family  (or parsed-family "")
     :font-weight  (parse-font-weight variant)
     :font-style   (parse-font-style variant)
     :variant-name variant}
    (let [{:keys [font-family font-weight font-style]}
          (parse-font-file-metadata filename)]
      {:font-family (or parsed-family font-family "")
       :font-weight font-weight
       :font-style  font-style})))

(defn font-weight->name
  [weight]
  (case (long weight)
    100 "Hairline"
    200 "Extra Light"
    300 "Light"
    400 "Regular"
    500 "Medium"
    600 "Semi Bold"
    700 "Bold"
    800 "Extra Bold"
    900 "Black"
    950 "Extra Black"))

(defn font-display-variant
  [variant-name weight style]
  (cond
    (and (string? variant-name) (not (str/blank? variant-name)))
    (str/trim variant-name)

    :else
    (let [base (font-weight->name weight)
          italic? (= "italic" style)]
      (cond-> base
        italic? (str " Italic")))))
