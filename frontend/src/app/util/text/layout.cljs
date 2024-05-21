;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.layout
  (:require
   ["./layout_impl.js" :as impl]
   [app.common.data.macros :as dm]
   [app.util.text.content :as content]))

;; Layouts text of an editor.
(def layout-from-editor impl/layoutFromEditor)

;; Layouts text of a shape.
(defn layout-from-shape
  "Layouts text of a shape"
  [shape]
  (let [content (:content shape)
        root (content/cljs->dom content)
        new-layout (impl/layoutFromRoot
                    root #js {:x (dm/get-prop shape :x)
                              :y (dm/get-prop shape :y)
                              :width (dm/get-prop shape :width)
                              :height (dm/get-prop shape :width)})]
    (js/console.log "new-layout-from-shape" new-layout)
    new-layout))
