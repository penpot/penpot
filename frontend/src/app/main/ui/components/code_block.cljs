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
   [app.util.dom :as dom]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc code-block
  {::mf/wrap-props false}
  [{:keys [code type]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        block-ref (mf/use-ref)
        code (str/trim code)]
    (mf/with-effect
      [code type]
      (when-let [node (mf/ref-val block-ref)]
        (dom/set-data! node "highlighted" nil)
        (hljs/highlightElement node)))

    (if new-css-system
      [:pre {:class (dm/str type " " (stl/css :code-display)) :ref block-ref} code]
      [:pre {:class (dm/str type " " "code-display") :ref block-ref} code])))

