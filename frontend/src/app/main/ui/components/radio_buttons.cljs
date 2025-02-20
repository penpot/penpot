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
  {::mf/props :obj}
  [{:keys [icon id value disabled title icon-class type]}]
  (let [context     (mf/use-ctx context)
        allow-empty (unchecked-get context "allow-empty")
        type        (if ^boolean type
                      type
                      (if ^boolean allow-empty
                        "checkbox"
                        "radio"))

        on-change   (unchecked-get context "on-change")
        selected    (unchecked-get context "selected")
        name        (unchecked-get context "name")

        encode-fn   (unchecked-get context "encode-fn")
        checked?    (= selected value)

        value       (encode-fn value)]


    [:label {:html-for id
             :title title
             :class (stl/css-case
                     :radio-icon true
                     :checked checked?
                     :disabled disabled)}

     (if (some? icon)
       [:span {:class icon-class} icon]
       [:span {:class (stl/css :title-name)} value])

     [:input {:id id
              :on-change on-change
              :type type
              :name name
              :disabled disabled
              :value value
              :default-checked checked?}]]))

(mf/defc radio-buttons
  {::mf/props :obj}
  [{:keys [name children on-change selected class wide encode-fn decode-fn allow-empty] :as props}]
  (let [encode-fn (d/nilv encode-fn identity)
        decode-fn (d/nilv decode-fn identity)
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
         (mf/deps selected on-change)
         (fn [event]
           (let [input (dom/get-target event)
                 value (dom/get-target-val event)

                 ;; Only allow null values when the "allow-empty" prop is true
                 value (when (or (not allow-empty)
                                 (not= value selected)) value)]
             (when (fn? on-change)
               (on-change (decode-fn value) event))
             (dom/blur! input))))

        context-value
        (mf/spread-object props
                          ;; We pass a special metadata for disable
                          ;; key casing transformation in this
                          ;; concrete case, because this component
                          ;; uses legacy mode and props are in
                          ;; kebab-case style
                          ^{::mf/transform false}
                          {:on-change on-change'
                           :encode-fn encode-fn
                           :decode-fn decode-fn})]

    [:& (mf/provider context) {:value context-value}
     [:div {:class (dm/str class " " (stl/css :radio-btn-wrapper))
            :style {:width width}
            :key (dm/str name "-" selected)}
      children]]))
