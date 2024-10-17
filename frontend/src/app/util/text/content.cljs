;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.text.content
  (:require
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
