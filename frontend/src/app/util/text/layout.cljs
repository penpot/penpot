;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.layout
  (:require
   ["./layout_impl.js" :as impl]
   [app.common.data.macros :as dm]
   [app.util.dom :as dom]
   [app.util.text.content :as content]))

;; Layouts text of an editor.
(def layout-from-editor impl/layoutFromEditor)

;; Element used to layout text of a shape.
(def shape-element (dom/create-element "div"))

;; Layouts text of a shape.
(defn layout-from-shape
  "Layouts text of a shape"
  [shape]
  (let [content (:content shape)
        root (content/cljs->dom content)]
    (dom/set-data! shape-element "x" (dm/get-prop shape :x))
    (dom/set-data! shape-element "y" (dm/get-prop shape :y))
    (dom/set-style! shape-element "width" (dm/get-prop shape :width))
    (dom/set-style! shape-element "height" (dm/get-prop shape :height))
    (.replaceChildren shape-element root)
    (impl/layoutFromElement shape-element)))
