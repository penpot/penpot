;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.radio-buttons
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.formats :as fmt]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def context
  (mf/create-context nil))

(mf/defc radio-button
  {::mf/wrap-props false}
  [props]
  (let [context   (mf/use-ctx context)

        icon       (unchecked-get props "icon")
        id         (unchecked-get props "id")
        value      (unchecked-get props "value")
        disabled   (unchecked-get props "disabled")
        title      (unchecked-get props "title")
        unique-key (unchecked-get props "unique-key")
        icon-class (unchecked-get props "icon-class")

        on-change  (unchecked-get context "on-change")
        selected   (unchecked-get context "selected")
        name       (unchecked-get context "name")
        encode-fn  (unchecked-get context "encode-fn")

        checked?   (= selected value)]

    [:label {:for id
             :title title
             :key unique-key
             :tabIndex "0"
             :class (stl/css-case
                     :radio-icon true
                     :checked checked?
                     :disabled disabled)}

     (if (some? icon)
       [:span {:class (when icon-class icon-class)}
        icon]
       [:span {:class (stl/css :title-name)}
        (encode-fn value)])

     [:input {:id id
              :on-change on-change
              :type "radio"
              :name name
              :disabled disabled
              :value (encode-fn value)
              :checked checked?}]]))

(mf/defc nilable-option
  {::mf/wrap-props false}
  [props]
  (let [context    (mf/use-ctx context)
        icon       (unchecked-get props "icon")
        id         (unchecked-get props "id")
        value      (unchecked-get props "value")
        disabled   (unchecked-get props "disabled")
        unique-key (unchecked-get props "unique-key")
        icon-class (unchecked-get props "icon-class")

        on-change  (unchecked-get context "on-change")
        selected   (unchecked-get context "selected")
        name       (unchecked-get context "name")

        encode-fn  (unchecked-get context "encode-fn")
        checked?   (= selected value)]

    [:label {:for id
             :key unique-key
             :class (stl/css-case
                     :radio-icon true
                     :disabled disabled
                     :checked checked?)}

     (if (some? icon)
       [:span {:class (when icon-class icon-class)}
        icon]
       [:span {:class (stl/css :title-name)}
        (encode-fn value)])

     [:input {:id id
              :on-change on-change
              :type "checkbox"
              :name name
              :disabled disabled
              :value (encode-fn value)
              :checked checked?}]]))

(mf/defc radio-buttons
  {::mf/wrap-props false}
  [props]
  (let [children  (unchecked-get props "children")
        on-change (unchecked-get props "on-change")
        selected  (unchecked-get props "selected")
        name      (unchecked-get props "name")
        class     (unchecked-get props "class")
        wide      (unchecked-get props "wide")

        encode-fn (d/nilv (unchecked-get props "encode-fn") identity)
        decode-fn (d/nilv (unchecked-get props "encode-fn") identity)

        nitems    (if (array? children)
                    (alength children)
                    1)

        width     (mf/with-memo [nitems]
                    (if (= wide true)
                      "unset"
                      (fmt/format-pixels
                       (+ (* 4 (- nitems 1))
                          (* 28 nitems)))))

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (when (fn? on-change)
               (on-change (decode-fn value) event)))))

        context-value
        (mf/with-memo [selected on-change' name encode-fn decode-fn]
          #js {:selected selected
               :on-change on-change'
               :name name
               :encode-fn encode-fn
               :decode-fn decode-fn})]

    [:& (mf/provider context) {:value context-value}
     [:div {:class (dm/str class " " (stl/css :radio-btn-wrapper))
            :style {:width width}}
      children]]))
