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

(def button-variants (set '("primary" "secondary" "ghost" "destructive")))

(mf/defc button*
  {::mf/props :obj}
  [{:keys [variant icon children class] :rest props}]
  (assert (or (nil? variant) (contains? button-variants variant) "expected valid variant"))
  (assert (or (nil? icon) (contains? icon-list icon) "expected valid icon id"))
  (let [variant (or variant "primary")
        class (dm/str class " " (stl/css-case :button true
                                              :button-primary (= variant "primary")
                                              :button-secondary (= variant "secondary")
                                              :button-ghost (= variant "ghost")
                                              :button-destructive (= variant "destructive")))
        props (mf/spread-props props {:class class})]
    [:> "button" props
     (when icon [:> icon* {:id icon :size "m"}])
     [:span {:class (stl/css :label-wrapper)} children]]))