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

(def ^:private schema:token-option
  [:map
   [:id {:optiona true} :string]
   [:ref some?]
   [:resolved {:optional true} [:or :int :string]]
   [:name {:optional true} :string]
   [:on-click {:optional true} fn?]
   [:selected {:optional true} :boolean]
   [:focused {:optional true} :boolean]])

(mf/defc token-option*
  {::mf/schema schema:token-option}
  [{:keys [id name on-click selected ref focused resolved] :rest props}]
  (let [internal-id (mf/use-id)
        id          (d/nilv id internal-id)]
    [:li {:value id
          :class (stl/css-case :token-option true
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
       name]]
     (when resolved
       [:> :span {:class (stl/css :option-pill)}
        resolved])]))
