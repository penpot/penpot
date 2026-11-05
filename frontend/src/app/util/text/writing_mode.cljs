;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.writing-mode
  "Text content as the active renderer lays it out. The CJK attrs only apply
  under the language that uses them: the text language, or the file language
  for a text without one (see `cjk/active-content`). Only the WASM renderer
  supports vertical writing; the SVG renderer lays every text out
  horizontally and leaves the stored attrs as they are."
  (:require
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]))

(defn vertical-layout-active?
  "True when the active renderer lays out vertical writing (WASM)."
  []
  cts/wasm-enabled?)

(defn get-file-language
  "Language of the open file for its texts without one, or nil."
  []
  (ctf/get-default-text-language (dsh/lookup-file-data @st/state)))

(defn active-content
  "Text content with the CJK attrs its language uses (see
  `cjk/active-content`)."
  [content]
  (cjk/active-content content (get-file-language)))

(defn content-writing-mode
  "Writing mode the active renderer lays the text content out with."
  [content]
  (when ^boolean (vertical-layout-active?)
    (cjk/active-writing-mode content (get-file-language))))

(defn vertical-text-content?
  "True when the text content flows vertically under the active renderer."
  [content]
  (= "vertical-rl" (content-writing-mode content)))

(defn vertical-values?
  "True when the text option `values` describe vertical text: vertical
  writing under a CJK language, the text one or else the file one. Mixed
  languages count as CJK."
  [values]
  (let [language (:text-language values)]
    (and ^boolean (vertical-layout-active?)
         (= "vertical-rl" (:writing-mode values))
         (or (= :multiple language)
             (some? (cjk/language-family (or language (get-file-language))))))))

(defn stale-vertical-layout?
  "True when the content is stored as vertical but the active renderer lays
  it out horizontally, so a WASM-computed layout (such as `:position-data`)
  does not match."
  [content]
  (and (not ^boolean (vertical-layout-active?))
       (cjk/stored-vertical? content)))

(defn renderable-paragraph
  "Paragraph attrs as the active renderer lays them out: without the vertical
  writing attrs when the renderer lays every text out horizontally."
  [paragraph]
  (if ^boolean (vertical-layout-active?)
    paragraph
    (apply dissoc paragraph cjk/vertical-writing-attrs)))

(defn renderable-content
  "Text content as the active renderer lays it out (see `active-content` and
  `renderable-paragraph`)."
  [content]
  (let [content (active-content content)]
    (if ^boolean (vertical-layout-active?)
      content
      (txt/transform-nodes txt/is-paragraph-node? renderable-paragraph content))))
