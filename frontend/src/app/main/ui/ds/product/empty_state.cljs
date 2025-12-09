;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.empty-state
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [rumext.v2 :as mf]))

(def ^:private schema:empty-state
  [:map
   [:class {:optional true} :string]
   [:icon [:and :string [:fn #(contains? icon-list %)]]]
   [:text :string]])

(mf/defc empty-state*
  {::mf/schema schema:empty-state}
  [{:keys [class icon text] :rest props}]
  (let [props (mf/spread-props props {:class [class (stl/css :group)]})]
    [:> :div props
     [:div {:class (stl/css :icon-wrapper)}
      [:> icon* {:icon-id icon
                 :size "l"
                 :class (stl/css :icon)}]]
     [:div {:class (stl/css :text)} text]]))
