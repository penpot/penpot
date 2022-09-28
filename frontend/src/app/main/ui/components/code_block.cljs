;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.code-block
  (:require
   ["highlight.js" :as hljs]
   [rumext.v2 :as mf]))

(mf/defc code-block [{:keys [code type]}]
  (let [block-ref (mf/use-ref)]
    (mf/use-effect
     (mf/deps code type block-ref)
     (fn []
       (hljs/highlightBlock (mf/ref-val block-ref))))
    [:pre.code-display {:class type
                        :ref block-ref} code]))

