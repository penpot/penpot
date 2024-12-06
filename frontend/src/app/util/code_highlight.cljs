;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-highlight
  (:require
   ["@penpot/hljs" :as hljs]
   [app.util.dom :as dom]))

(defn highlight!
  [node]
  (dom/set-data! node "highlighted" nil)
  (hljs/highlightElement node))
