;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.code-block
  (:require-macros [app.main.style :as stl])
  (:require
   ["highlight.js" :as hljs]
   [app.common.data.macros :as dm]
   [app.main.ui.context :as ctx]
   [rumext.v2 :as mf]))

(mf/defc code-block
  {::mf/wrap-props false}
  [{:keys [code type]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        block-ref (mf/use-ref)]
    (mf/with-effect [code type]
      (when-let [node (mf/ref-val block-ref)]
        (hljs/highlightElement node)))

    [:pre {:class (dm/str type " " (stl/css new-css-system :code-display)) :ref block-ref} code]))

