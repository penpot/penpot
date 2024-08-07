;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.buttons.icon-button
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [rumext.v2 :as mf]))

(def button-variants (set '("primary" "secondary" "ghost" "destructive")))

(mf/defc icon-button*
  {::mf/props :obj}
  [{:keys [class icon variant aria-label] :rest props}]
  (assert (contains? icon-list icon) "expected valid icon id")
  (assert (or (not variant) (contains? button-variants variant)) "expected valid variant")
  (assert (some? aria-label) "aria-label must be provided")
  (let [variant (or variant "primary")
        class (dm/str class " " (stl/css-case :icon-button true
                                              :icon-button-primary (= variant "primary")
                                              :icon-button-secondary (= variant "secondary")
                                              :icon-button-ghost (= variant "ghost")
                                              :icon-button-destructive (= variant "destructive")))
        props (mf/spread-props props {:class class :title aria-label})]
    [:> "button" props [:> icon* {:id icon :aria-label aria-label}]]))