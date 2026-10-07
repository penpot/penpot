;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.common
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
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

(defn radio-selected
  "Value to select in a radio group for an attribute value: its name, or
  `default` when unset. Mixed (`:multiple`) values select nothing."
  ([value]
   (radio-selected value ""))
  ([value default]
   (cond
     (= value :multiple) ""
     (or (nil? value)
         (and (string? value) (empty? value))) default
     (keyword? value) (d/name value)
     (string? value) value
     :else (str value))))

(defn emit-value-or-token [value emit-value-fn ids attrs]
  (if (or (string? value)
          (number? value)
          (nil? value))
    (emit-value-fn value)
    (st/emit!
     (dwta/toggle-token {:token     (first value)
                         :attrs     attrs
                         :shape-ids ids}))))

(defn tokens-allowed-position?
  "Design tokens only apply to the first fill or stroke in a shape's ordered list,
  so token controls are only enabled for the entry at `index` zero.

  `first-only?` marks the lists where that rule applies. Other color rows
  (shadows, gradients, the selection color list) reuse `index` for their own row
  order and must keep their token controls enabled."
  [first-only? index]
  (or (not first-only?)
      (and (some? index) (zero? index))))

