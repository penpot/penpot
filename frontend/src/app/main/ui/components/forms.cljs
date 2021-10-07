;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.components.forms
  (:require
   [app.common.data :as d]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def form-ctx (mf/create-context nil))
(def use-form fm/use-form)

(mf/defc input
  [{:keys [label help-icon disabled form hint trim children] :as props}]
  (let [input-type   (get props :type "text")
        input-name   (get props :name)
        more-classes (get props :class)
        auto-focus?  (get props :auto-focus? false)

        form         (or form (mf/use-ctx form-ctx))

        type'        (mf/use-state input-type)
        focus?       (mf/use-state false)

        is-checkbox? (= @type' "checkbox")
        is-radio?    (= @type' "radio")
        is-text?     (or (= @type' "password")
                         (= @type' "text")
                         (= @type' "email"))

        touched?     (get-in @form [:touched input-name])
        error        (get-in @form [:errors input-name])

        value        (get-in @form [:data input-name] "")

        help-icon'   (cond
                       (and (= input-type "password")
                            (= @type' "password"))
                       i/eye

                       (and (= input-type "password")
                            (= @type' "text"))
                       i/eye-closed

                       :else
                       help-icon)

        klass (str more-classes " "
                   (dom/classnames
                     :focus          @focus?
                     :valid          (and touched? (not error))
                     :invalid        (and touched? error)
                     :disabled       disabled
                     :empty          (and is-text? (str/empty? value))
                     :with-icon      (not (nil? help-icon'))
                     :custom-input   is-text?
                     :input-radio    is-radio?
                     :input-checkbox is-checkbox?))

        swap-text-password
        (fn []
          (swap! type' (fn [input-type]
                         (if (= "password" input-type)
                           "text"
                           "password"))))

        on-focus  #(reset! focus? true)
        on-change (fm/on-input-change form input-name trim)

        on-blur
        (fn [_]
          (reset! focus? false)
          (when-not (get-in @form [:touched input-name])
            (swap! form assoc-in [:touched input-name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim :children)
                  (assoc :id (name input-name)
                         :value value
                         :auto-focus auto-focus?
                         :on-focus on-focus
                         :on-blur on-blur
                         :placeholder label
                         :on-change on-change
                         :type @type')
                  (obj/clj->props))]

    [:div
     {:class klass}
     [:*
      [:> :input props]
      (cond
        (some? label)
        [:label {:for (name input-name)} label]

        (some? children)
        [:label {:for (name input-name)} children])

      (when help-icon'
        [:div.help-icon
         {:style {:cursor "pointer"}
          :on-click (when (= "password" input-type)
                      swap-text-password)}
         help-icon'])
      (cond
        (and touched? (:message error))
        [:span.error (tr (:message error))]

        (string? hint)
        [:span.hint hint])]]))


(mf/defc textarea
  [{:keys [label disabled form hint trim] :as props}]
  (let [input-name (get props :name)

        form     (or form (mf/use-ctx form-ctx))

        focus?   (mf/use-state false)

        touched? (get-in @form [:touched input-name])
        error    (get-in @form [:errors input-name])

        value    (get-in @form [:data input-name] "")

        klass    (dom/classnames
                  :focus     @focus?
                  :valid     (and touched? (not error))
                  :invalid   (and touched? error)
                  :disabled  disabled
                  ;; :empty     (str/empty? value)
                  )

        on-focus  #(reset! focus? true)
        on-change (fm/on-input-change form input-name trim)

        on-blur
        (fn [_]
          (reset! focus? false)
          (when-not (get-in @form [:touched input-name])
            (swap! form assoc-in [:touched input-name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim)
                  (assoc :value value
                         :on-focus on-focus
                         :on-blur on-blur
                         ;; :placeholder label
                         :on-change on-change)
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
  [{:keys [options label form default] :as props
    :or {default ""}}]
  (let [input-name (get props :name)

        form      (or form (mf/use-ctx form-ctx))
        value     (or (get-in @form [:data input-name]) default)
        cvalue    (d/seek #(= value (:value %)) options)
        on-change (fm/on-input-change form input-name)]

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
