;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.writing-mode
  "Writing mode of text content as the active renderer lays it out. Only the
  WASM renderer supports vertical writing; the SVG renderer lays every text
  out horizontally and leaves the stored attrs as they are."
  (:require
   [app.common.types.shape :as cts]
   [app.common.types.text.japanese-layout :as jl]))

(defn vertical-layout-active?
  "True when the active renderer lays out vertical writing (WASM)."
  []
  cts/wasm-enabled?)

(defn content-writing-mode
  "Effective writing mode of a text content (see `jl/content-writing-mode`)."
  [content]
  (when ^boolean (vertical-layout-active?)
    (jl/content-writing-mode content)))

(defn vertical-text-content?
  "True when the text content flows vertically under the active renderer."
  [content]
  (and ^boolean (vertical-layout-active?)
       (jl/vertical-text-content? content)))

(defn stale-vertical-layout?
  "True when the content is stored as vertical but the active renderer lays
  it out horizontally, so a WASM-computed layout (such as `:position-data`)
  does not match."
  [content]
  (and (not ^boolean (vertical-layout-active?))
       (jl/vertical-text-content? content)))
