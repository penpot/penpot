;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.shared.options-dropdown
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.util.array :as array]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(mf/defc option*
  {::mf/private true}
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
       :class (stl/css :option-check)
       :aria-hidden (when label true)}])])

(mf/defc options-dropdown*
  {::mf/props :obj}
  [{:keys [set-ref on-click options selected focused empty-to-end] :rest props}]
  (let [props (mf/spread-props props
                               {:class (stl/css :option-list)
                                :tab-index "-1"
                                :role "listbox"})

        options-blank (when empty-to-end
                        (array/filter #(str/blank? (obj/get % "id")) options))
        options       (if empty-to-end
                        (array/filter #((complement str/blank?) (obj/get % "id")) options)
                        options)]

    [:> "ul" props
     (for [option ^js options]
       (let [id    (obj/get option "id")
             label (obj/get option "label")
             aria-label (obj/get option "aria-label")
             icon (obj/get option "icon")]
         [:> option* {:selected (= id selected)
                      :key id
                      :id id
                      :label label
                      :icon icon
                      :aria-label aria-label
                      :set-ref set-ref
                      :focused (= id focused)
                      :dimmed false
                      :on-click on-click}]))

     (when (seq options-blank)
       [:*
        (when (seq options)
          [:hr {:class (stl/css :option-separator)}])

        (for [option ^js options-blank]
          (let [id    (obj/get option "id")
                label (obj/get option "label")
                aria-label (obj/get option "aria-label")
                icon (obj/get option "icon")]
            [:> option* {:selected (= id selected)
                         :key id
                         :id id
                         :label label
                         :icon icon
                         :aria-label aria-label
                         :set-ref set-ref
                         :focused (= id focused)
                         :dimmed true
                         :on-click on-click}]))])]))
