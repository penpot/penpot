;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.render-wasm.api.texts
  (:require
   [app.common.render-wasm.text-content :as tc]
   [app.render-wasm.api.fonts :as f]))

(defn write-shape-text
  "Workspace text serialization: the byte writing is shared via
  `app.common.render-wasm.text-content`; font resolution is the workspace's (fonts DB)."
  [spans paragraph text]
  (tc/write-shape-text! spans paragraph text
                        {:normalize-paragraph f/normalize-paragraph-font
                         :normalize-span      f/normalize-span-font}))
