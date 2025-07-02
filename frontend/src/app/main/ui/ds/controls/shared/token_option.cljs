;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns  app.main.ui.ds.controls.shared.token-option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [rumext.v2 :as mf]))

;; TODO: Review schema props
(def schema:token-option
  [:map {:title "token option"}
   [:id {:optiona true} :string]
   [:resolved-value {:optional true}
    [:or
     :int
     :string]]
   [:name {:optional true} :string]])

(mf/defc token-option*
  [{:keys [id name on-click selected ref focused resolved group separator] :rest props}]
  (let [internal-id (mf/use-id)
        id          (or id internal-id)]
 (cond
   group
   [:li {:class (stl/css :group-option)}
    [:> icon*
     {:icon-id i/arrow-down
      :size "m"
      :class (stl/css :option-check)
      :aria-hidden (when name true)}]
    (d/name name)]

   separator
   [:li [:hr {:class (stl/css :option-separator)}]]

   :else
   [:li {:value id
         :class (stl/css-case :option true
                              :option-with-pill true
                              :option-selected-token selected
                              :option-current focused)
         :aria-selected selected
         :ref ref
         :role "option"
         :id id
         :on-click on-click
         :data-id id
         :data-testid "dropdown-option"}

    (if selected
      [:> icon*
       {:icon-id i/tick
        :size "s"
        :class (stl/css :option-check)
        :aria-hidden (when name true)}]
      [:span {:class (stl/css :icon-placeholder)}])
    [:> tooltip* {:content name
                  :id (dm/str id "-name")
                  :class (stl/css :option-text)}
     ;;  Add ellipsis
      [:span {:aria-labelledby (dm/str id "-name")}
       name]
     ]
    (when resolved
      [:> :span {:class (stl/css :option-pill)}
       resolved])])))
