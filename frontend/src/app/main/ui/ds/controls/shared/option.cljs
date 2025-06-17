
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns  app.main.ui.ds.controls.shared.option
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [rumext.v2 :as mf]))

(def ^:private schema:option
  [:and
   [:map {:title "option"}
    [:id :string]
    [:icon {:optional true}
     [:and :string [:fn #(contains? icon-list %)]]]
    [:label {:optional true} :string]
    [:aria-label {:optional true} :string]]
   [:fn {:error/message "invalid data: missing required props"}
    (fn [option]
      (or (and (contains? option :icon)
               (or (contains? option :label)
                   (contains? option :aria-label)))
          (contains? option :label)))]])

(mf/defc option*
  {::mf/schema schema:option}
  [{:keys [id label icon aria-label on-click selected set-ref focused dimmed] :rest props}]

  [:> :li {:value id
           :class (stl/css-case :option true
                                :option-with-icon (some? icon)
                                :option-selected selected
                                :option-current focused)
           :aria-selected selected
           :ref (fn [node]
                  (set-ref node id))
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
                                :option-text-dimmed dimmed)} label]
   (when selected
     [:> icon*
      {:icon-id i/tick
       :size "s"
       :class (stl/css :option-icon)
       :aria-hidden (when label true)}])])