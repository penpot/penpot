;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc advanced-options*
  [{:keys [class is-visible children]}]
  (let [ref (mf/use-ref nil)]
    (mf/use-effect
     (mf/deps is-visible)
     (fn []
       (when-let [node (mf/ref-val ref)]
         (when is-visible
           (dom/scroll-into-view-if-needed! node)))))
    (when is-visible
      [:div {:class [class (stl/css :advanced-options-wrapper)]
             :ref ref}
       children])))

(defn emit-value-or-token [value emit-value-fn ids attrs]
  (cond
    (nil? value)
    (emit-value-fn nil)

    (or (string? value) (number? value))
    (emit-value-fn value)

    :else
    (st/emit!
     (dwta/toggle-token {:token     (first value)
                         :attrs     attrs
                         :shape-ids ids}))))

