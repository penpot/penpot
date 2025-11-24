;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content
  (:require
   [app.common.types.text :as txt]
   [app.main.refs :as refs]
   [app.util.text.content.from-dom :as fd]
   [app.util.text.content.to-dom :as td]))

(defn dom->cljs
  "Gets the editor content from a DOM structure"
  [root]
  (fd/create-root root))

(defn cljs->dom
  "Sets the editor content from a CLJS structure"
  [root]
  (td/create-root root))

(defn v2-default-text-content
  "Build the base text tree (root -> paragraph-set -> paragraph -> span) with the
  current default typography. Used by the V2 editor/WASM path when a shape is
  created with no content yet."
  []
  (let [default-font  (deref refs/default-font)
        text-defaults (merge (txt/get-default-text-attrs) default-font)
        default-span  (merge {:text ""}
                             (select-keys text-defaults txt/text-node-attrs))
        default-paragraph (merge {:type "paragraph"
                                  :children [default-span]}
                                 (select-keys text-defaults txt/paragraph-attrs))
        default-paragraph-set {:type "paragraph-set"
                               :children [default-paragraph]}]
    (merge {:type "root"
            :children [default-paragraph-set]}
           txt/default-root-attrs
           (select-keys text-defaults txt/root-attrs))))
