;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.buttons.button
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [rumext.v2 :as mf]))

(def ^:private schema:button
  [:map
   [:class {:optional true} :string]
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:on-ref {:optional true} fn?]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive"]]]])

(mf/defc button*
  {::mf/props :obj
   ::mf/schema schema:button}
  [{:keys [variant icon children class on-ref] :rest props}]
  (let [variant (or variant "primary")
        class (dm/str class " " (stl/css-case :button true
                                              :button-primary (= variant "primary")
                                              :button-secondary (= variant "secondary")
                                              :button-ghost (= variant "ghost")
                                              :button-destructive (= variant "destructive")))
        props (mf/spread-props props {:class class
                                      :ref (fn [node]
                                             (when on-ref
                                               (on-ref node)))})]
    [:> "button" props
     (when icon [:> icon* {:icon-id icon :size "m"}])
     [:span {:class (stl/css :label-wrapper)} children]]))