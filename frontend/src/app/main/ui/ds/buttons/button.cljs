;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.buttons.button
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [rumext.v2 :as mf]))

(def ^:private schema:button
  [:map
   [:class {:optional true} :string]
   [:type {:optional true} [:maybe [:enum "button" "submit" "reset"]]]
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:on-ref {:optional true} fn?]
   [:to {:optional true} :string] ;; renders as an anchor element
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive"]]]])

(mf/defc button*
  {::mf/schema schema:button}
  [{:keys [variant icon children class on-ref to type] :rest props}]
  (let [variant (d/nilv variant "primary")
        element (if to "a" "button")
        internal-class (stl/css-case :button true
                                     :button-link (some? to)
                                     :button-primary (= variant "primary")
                                     :button-secondary (= variant "secondary")
                                     :button-ghost (= variant "ghost")
                                     :button-destructive (= variant "destructive"))
        props (mf/spread-props props {:class [class internal-class]
                                      :href to
                                      :type (d/nilv type "button")
                                      :ref (fn [node]
                                             (when on-ref
                                               (on-ref node)))})]
    [:> element props
     (when icon [:> icon* {:icon-id icon :size "m"}])
     [:span {:class (stl/css :label-wrapper)} children]]))
