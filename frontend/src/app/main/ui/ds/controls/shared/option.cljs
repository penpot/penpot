
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns  app.main.ui.ds.controls.shared.option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:option
  "A schema for option* component props"
  [:and
   [:map {:title "option"}
    [:id :string]
    [:icon {:optional true} [:maybe :string]]
    [:selected {:optional true} :boolean]
    [:focused {:optional true} :boolean]
    [:dimmed {:optional true} :boolean]
    [:label {:optional true} :string]
    [:aria-label {:optional true} [:maybe :string]]
    [:on-click {:optional true} fn?]]
   [:fn {:error/message "invalid data: missing required props"}
    (fn [props]
      (or (and (contains? props :icon)
               (or (contains? props :label)
                   (contains? props :aria-label)))
          (contains? props :label)))]])

(mf/defc option*
  {::mf/schema schema:option}
  [{:keys [id ref label icon aria-label on-click selected focused dimmed] :rest props}]
  (let [class (stl/css-case :option true
                            :option-with-icon (some? icon)
                            :option-selected selected
                            :option-current focused)]

    [:li {:value id
          :class class
          :aria-selected selected
          :ref ref
          :role "option"
          :id id
          :on-click on-click
          :data-id id
          :data-testid "dropdown-option"}

     (when (some? icon)
       [:> icon*
        {:icon-id icon
         :size "s"
         :class (stl/css :option-icon)
         :aria-hidden (when label true)
         :aria-label  (when (not label) aria-label)}])

     [:span {:class (stl/css-case :option-text true
                                  :option-text-dimmed dimmed)}
      label]

     (when ^boolean selected
       [:> icon*
        {:icon-id i/tick
         :size "s"
         :class (stl/css :option-check)
         :aria-hidden (when ^boolean label true)}])]))
