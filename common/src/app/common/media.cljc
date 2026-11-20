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

(def storage-object-types
  "Every content-type the system stores in storage objects: uploaded
  images and fonts, generated thumbnails, temporary files and the
  `application/octet-stream` fallback used by file data and font
  variants. Sorted for determinism. Every member must have a
  producing code path; anticipated but unproduced types (such as
  `image/apng`) stay out until some flow actually stores them.
  Import boundaries normalize the stored string (lowercase, no
  parameters) before checking membership, so legacy spelling
  variants keep importing while unknown types are rejected."
  (into (sorted-set)
        (concat image-types
                font-types
                ["application/octet-stream"
                 "application/pdf"
                 "application/zip"])))

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

(def ^:private font-variant-tokens
  "Weight and style tokens stripped from a font name to derive its family.
  Keep the alternatives in sync with `parse-font-weight` and
  `parse-font-style`."
  ["extra\\s*black" "ultra\\s*black" "extra\\s*bold" "ultra\\s*bold"
   "semi\\s*bold" "demi\\s*bold" "extra\\s*light" "ultra\\s*light"
   "hairline" "thin" "light" "normal" "regular" "medium" "bold" "black"
   "heavy" "solid" "italic" "oblique"])

(def ^:private font-variant-tokens-re
  ;; The trailing boundary is a lookahead, so two tokens separated by a single
  ;; space are both stripped in one pass (the separator is not consumed).
  (re-pattern (str "(?i)(^|[-_\\s])(" (str/join "|" font-variant-tokens) ")(?=[-_\\s]|$)")))

(def ^:private font-concatenated-style-re
  ;; Separate a style token glued to a weight token, e.g. "BoldItalic" ->
  ;; "Bold Italic". Only a known weight token followed by a style token is
  ;; split, so families such as "OpenSans" are left untouched. The trailing
  ;; boundary is a lookahead, so a glued style token followed by a technical
  ;; suffix (e.g. "Italic_v2") is split too.
  #"(?i)(black|bold|heavy|solid|medium|light|thin|hairline|regular|normal)(italic|oblique)(?=[-_\s]|$)")

(def ^:private font-technical-suffix-re
  ;; Trailing foundry/build suffixes, e.g. "Roboto-Bold-VF" or "Inter_v2".
  ;; Requires a separator; stripped before token detection so the parsers
  ;; interpret the name the same way.
  #"(?i)[-_\s]+(?:vf|v[0-9]+)$")

(defn- normalize-font-name
  "Normalize a font name or variant string before token detection:
  split a style token glued to a weight token, then strip a trailing
  technical suffix. Applied by `parse-font-family`, `parse-font-weight`
  and `parse-font-style` so all three interpret the name the same way."
  [s]
  (-> s
      (str/replace font-concatenated-style-re "$1 $2")
      (str/replace font-technical-suffix-re "")))

(defn parse-font-family
  "Derive a font family name from a base name (a filename without its
  extension), stripping the known weight/style tokens."
  [base-name]
  (let [normalized (normalize-font-name base-name)
        stripped   (-> normalized
                       (str/replace font-variant-tokens-re "$1")
                       (str/replace #"[-_\s]+" " ")
                       (str/trim))]
    (if (str/blank? stripped) base-name stripped)))

(defn parse-font-weight
  "Parses a font variant/subfamily string into a numeric weight.
  A nil or non-string variant means the font carries no subfamily
  metadata, so it defaults to 400 (regular), the same as a blank string."
  [variant]
  (let [variant (normalize-font-name (if (string? variant) variant ""))]
    (cond
      (re-seq #"(?i)(?:^|[-_\s])(hairline|thin)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)               100
      (re-seq #"(?i)(?:^|[-_\s])(extra\s*light|ultra\s*light)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant) 200
      (re-seq #"(?i)(?:^|[-_\s])(light)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)                       300
      (re-seq #"(?i)(?:^|[-_\s])(normal|regular)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)              400
      (re-seq #"(?i)(?:^|[-_\s])(medium)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)                      500
      (re-seq #"(?i)(?:^|[-_\s])(semi\s*bold|demi\s*bold)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)     600
      (re-seq #"(?i)(?:^|[-_\s])(extra\s*bold|ultra\s*bold)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)   800
      (re-seq #"(?i)(?:^|[-_\s])(bold)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)                        700
      (re-seq #"(?i)(?:^|[-_\s])(extra\s*black|ultra\s*black)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant) 950
      (re-seq #"(?i)(?:^|[-_\s])(black|heavy|solid)(?=(?:[-_\s]|$|italic\b|oblique\b))" variant)           900
      :else                                                                                                400)))

(defn parse-font-style
  "Parses a font variant/subfamily string into \"italic\" or \"normal\".
  A nil or non-string variant means the font carries no subfamily
  metadata, so it defaults to \"normal\"."
  [variant]
  (let [variant (normalize-font-name (if (string? variant) variant ""))]
    (if (or (re-seq #"(?i)(?:^|[-_\s])(italic|oblique)(?:[-_\s]|$)" variant)
            (re-seq #"(?i)(?:italic|oblique)$" variant))
      "italic"
      "normal")))

(defn first-nonblank-string
  "Return the first nonblank string from the candidates, or nil."
  [& candidates]
  (first (filter #(and (string? %) (not (str/blank? %))) candidates)))

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
