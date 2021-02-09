;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.components.forms
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.main.ui.icons :as i]
   [app.util.object :as obj]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [t tr]]
   ["react" :as react]
   [app.util.dom :as dom]))

(def form-ctx (mf/create-context nil))
(def use-form fm/use-form)

(mf/defc input
  [{:keys [type label help-icon disabled name form hint trim] :as props}]
  (let [form       (or form (mf/use-ctx form-ctx))

        type'      (mf/use-state type)
        focus?     (mf/use-state false)

        touched?   (get-in @form [:touched name])
        error      (get-in @form [:errors name])

        value      (get-in @form [:data name] "")

        help-icon' (cond
                     (and (= type "password")
                          (= @type' "password"))
                     i/eye

                     (and (= type "password")
                          (= @type' "text"))
                     i/eye-closed

                     :else
                     help-icon)

        klass (dom/classnames
               :focus     @focus?
               :valid     (and touched? (not error))
               :invalid   (and touched? error)
               :disabled  disabled
               :empty     (str/empty? value)
               :with-icon (not (nil? help-icon')))

        swap-text-password
        (fn []
          (swap! type' (fn [type]
                         (if (= "password" type)
                           "text"
                           "password"))))

        on-focus  #(reset! focus? true)
        on-change (fm/on-input-change form name trim)

        on-blur
        (fn [event]
          (reset! focus? false)
          (when-not (get-in @form [:touched name])
            (swap! form assoc-in [:touched name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim)
                  (assoc :value value
                         :on-focus on-focus
                         :on-blur on-blur
                         :placeholder label
                         :on-change on-change
                         :type @type')
                  (obj/clj->props))]

    [:div.custom-input
     {:class klass}
     [:*
      [:label label]
      [:> :input props]
      (when help-icon'
        [:div.help-icon
         {:style {:cursor "pointer"}
          :on-click (when (= "password" type)
                      swap-text-password)}
         help-icon'])
      (cond
        (and touched? (:message error))
        [:span.error (tr (:message error))]

        (string? hint)
        [:span.hint hint])]]))


(mf/defc textarea
  [{:keys [label disabled name form hint trim] :as props}]
  (let [form     (or form (mf/use-ctx form-ctx))

        type'    (mf/use-state type)
        focus?   (mf/use-state false)

        touched? (get-in @form [:touched name])
        error    (get-in @form [:errors name])

        value    (get-in @form [:data name] "")

        klass    (dom/classnames
                  :focus     @focus?
                  :valid     (and touched? (not error))
                  :invalid   (and touched? error)
                  :disabled  disabled
                  ;; :empty     (str/empty? value)
                  )

        on-focus  #(reset! focus? true)
        on-change (fm/on-input-change form name trim)

        on-blur
        (fn [event]
          (reset! focus? false)
          (when-not (get-in @form [:touched name])
            (swap! form assoc-in [:touched name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim)
                  (assoc :value value
                         :on-focus on-focus
                         :on-blur on-blur
                         ;; :placeholder label
                         :on-change on-change
                         :type @type')
                  (obj/clj->props))]

    [:div.custom-input
     {:class klass}
     [:*
      [:label label]
      [:> :textarea props]
      (cond
        (and touched? (:message error))
        [:span.error (tr (:message error))]

        (string? hint)
        [:span.hint hint])]]))

(mf/defc select
  [{:keys [options label name form default]
    :or {default ""}}]
  (let [form      (or form (mf/use-ctx form-ctx))
        value     (get-in @form [:data name] default)
        cvalue    (d/seek #(= value (:value %)) options)
        on-change (fm/on-input-change form name)]

    [:div.custom-select
     [:select {:value value
               :on-change on-change}
      (for [item options]
        [:option {:key (:value item) :value (:value item)} (:label item)])]

     [:div.input-container
      [:div.main-content
       [:label label]
       [:span.value (:label cvalue "")]]

      [:div.icon
       i/arrow-slide]]]))

(mf/defc submit-button
  [{:keys [label form on-click disabled] :as props}]
  (let [form (or form (mf/use-ctx form-ctx))]
    [:input.btn-primary.btn-large
     {:name "submit"
      :class (when-not (:valid @form) "btn-disabled")
      :disabled (or (not (:valid @form)) (true? disabled))
      :on-click on-click
      :value label
      :type "submit"}]))

(mf/defc form
  [{:keys [on-submit form children class] :as props}]
  (let [on-submit (or on-submit (constantly nil))]
    [:& (mf/provider form-ctx) {:value form}
     [:form {:class class
             :on-submit (fn [event]
                          (dom/prevent-default event)
                          (on-submit form event))}
      children]]))
