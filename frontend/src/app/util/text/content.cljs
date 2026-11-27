;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.content
  (:require
   [app.common.types.text :as txt]
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.util.text.content.from-dom :as fd]
   [app.util.text.content.to-dom :as td]))

(defn dom->cljs
  "Gets the editor content from a DOM structure"
  [root]
  (when (some? root)
    (fd/create-root root)))

(defn cljs->dom
  "Sets the editor content from a CLJS structure"
  [root]
  (td/create-root root))

(defn new-text-language-attrs
  "Paragraph attrs of the language new texts take: the one used last in
  this session, else the file language. None while CJK support is off, as
  nothing in the UI would show or remove it."
  []
  ;; The resolved feature set (see `app.main.features/use-feature`); that
  ;; namespace depends on this one through the renderer.
  (when (contains? (:features @st/state) "cjk-support")
    (when-let [language (or (deref refs/default-text-language)
                            (deref refs/file-text-language))]
      (cjk/chosen-language-attrs language nil))))

(defn v2-default-text-content
  "Build the base text tree (root -> paragraph-set -> paragraph -> span) with the
  current default typography and the language new texts take: the one used
  last in this session, else the file language. Used by the V2 editor/WASM
  path when a shape is created with no content yet."
  []
  (let [default-font  (deref refs/default-font)
        text-defaults (merge (txt/get-default-text-attrs) default-font
                             (new-text-language-attrs))
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
