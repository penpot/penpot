;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.code-highlight
  (:require
   ["highlight.js" :as hljs]
   [app.util.dom :as dom]))

(defn highlight!
  {:lazy-loadable true}
  [node]
  (dom/set-data! node "highlighted" nil)
  (.highlightElement hljs/default node))
