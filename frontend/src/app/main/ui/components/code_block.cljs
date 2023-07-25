;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.code-block
  (:require
   ["highlight.js" :as hljs]
   [rumext.v2 :as mf]))

(mf/defc code-block
  {::mf/wrap-props false}
  [{:keys [code type]}]
  (let [block-ref (mf/use-ref)]
    (mf/with-effect [code type]
      (when-let [node (mf/ref-val block-ref)]
        (hljs/highlightElement node)))

    [:pre.code-display {:class type :ref block-ref} code]))

