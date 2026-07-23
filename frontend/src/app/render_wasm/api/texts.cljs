;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.api.texts
  (:require
   [app.render-wasm.api.fonts :as f]
   [app.render-wasm.fallback-fonts :as fbf]
   [app.render-wasm.text-content :as tc]))

(defn write-shape-text
  "Workspace text serialization: the byte writing is shared via
  `app.render-wasm.text-content`; font resolution is the workspace's (fonts DB)."
  [spans paragraph text]
  (tc/write-shape-text! spans paragraph text
                        {:normalize-font-id   f/normalize-font-id
                         :normalize-paragraph f/normalize-paragraph-font
                         :normalize-span      f/normalize-span-font}))

;; Emoji/script detection lives in the host-agnostic
;; `app.render-wasm.fallback-fonts`; kept re-exported here for existing
;; workspace callers.
(def contains-emoji? fbf/contains-emoji?)
(def collect-used-languages fbf/collect-used-languages)
