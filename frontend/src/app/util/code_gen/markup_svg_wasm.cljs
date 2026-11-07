;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.code-gen.markup-svg-wasm
  "SVG markup via the render-wasm export path (inspect / copy-as-svg).
  Callers must gate on `render-wasm/v1` and must not fall back to classic
  SVG codegen while that feature is on."
  (:require
   [app.render-wasm.api :as wasm.api]
   [app.util.code-beautify :as cb]
   [cuerdas.core :as str]))

(defn- decode-utf8
  [bytes]
  (when bytes
    (.decode (js/TextDecoder.) bytes)))

(defn get-svg-markup
  "UTF-8 SVG string for the given shape ids via the main-thread WASM module.
  Empty string when WASM is not ready, ids are empty, or export throws."
  [shape-ids]
  (let [ids (vec shape-ids)]
    (cond
      (empty? ids)
      ""

      (not (wasm.api/initialized?))
      ""

      :else
      (try
        (or (decode-utf8
             (if (= 1 (count ids))
               (wasm.api/render-shape-svg (first ids) 1)
               (wasm.api/render-shapes-svg ids 1)))
            "")
        ;; Missing shape / export failure is a WASM CriticalError (:panic).
        ;; Letting it reach the global handler would show Internal Error and
        ;; block the workspace; the user can still work without this markup, so
        ;; log and return empty instead (same pattern as other non-fatal WASM UI paths).
        (catch :default cause
          (js/console.error "WASM SVG markup failed:" cause)
          "")))))

(defn get-formatted-svg-markup
  "Beautified SVG markup for inspect / copy-as-svg.
  Skips beautify when markup is empty so inspect/copy stay safe without
  js-beautify (and avoid a no-op format call)."
  [shape-ids]
  (let [markup (get-svg-markup shape-ids)]
    (if (str/empty? markup)
      ""
      (cb/format-code markup "svg"))))
