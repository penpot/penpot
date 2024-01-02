;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc advanced-options [{:keys [visible? class children]}]
  (let [ref (mf/use-ref nil)]
    (mf/use-effect
     (mf/deps visible?)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when visible?
           (dom/scroll-into-view-if-needed! node)))))
    (when visible?
      [:div {:class (dm/str class " " (stl/css :advanced-options-wrapper))
             :ref ref}
       children])))

