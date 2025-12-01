;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.checkbox
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [rumext.v2 :as mf]))

(def ^:private schema:checkbox
  [:map
   [:id {:optional true} :string]
   [:label {:optional true} :string]
   [:checked {:optional true} :boolean]
   [:on-change {:optional true} fn?]
   [:disabled {:optional true} :boolean]])

(mf/defc checkbox*
  {::mf/schema schema:checkbox}
  [{:keys [id class label checked on-change disabled] :rest props}]
  (let [props
        (mf/spread-props props {:type "checkbox"
                                :class (stl/css :checkbox-input)
                                :id id
                                :checked checked
                                :on-change on-change
                                :disabled disabled})]

    [:div {:class [class (stl/css :checkbox)]}
     [:label {:for id
              :class (stl/css :checkbox-label)}
      [:div {:class (stl/css-case :checkbox-box true
                                  :checked checked
                                  :disabled disabled)}
       (when checked
         [:> icon* {:icon-id i/tick
                    :size "s"}])]

      [:div {:class (stl/css :checkbox-text)} label]

      [:> :input props]]]))
