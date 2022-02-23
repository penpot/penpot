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
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [clojure.string]
   [cuerdas.core :as str]
   [rumext.alpha :as mf]))

(def form-ctx (mf/create-context nil))
(def use-form fm/use-form)

(mf/defc input
  [{:keys [label help-icon disabled form hint trim children data-test] :as props}]
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
        on-change (fn [event]
                    (let [value  (-> event dom/get-target dom/get-input-value)]
                      (fm/on-input-change form input-name value trim)))

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
        [:span.error {:data-test (clojure.string/join [data-test "-error"]) }(tr (:message error))]

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
        on-change (fn [event]
                    (let [target (dom/get-target event)
                          value  (dom/get-value target)]
                      (fm/on-input-change form input-name value trim)))

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
  [{:keys [options label form default data-test] :as props
    :or {default ""}}]
  (let [input-name (get props :name)
        form      (or form (mf/use-ctx form-ctx))
        value     (or (get-in @form [:data input-name]) default)
        cvalue    (d/seek #(= value (:value %)) options)
        on-change (fn [event]
                    (let [target (dom/get-target event)
                          value  (dom/get-value target)]
                      (fm/on-input-change form input-name value)))]

    [:div.custom-select
     [:select {:value value
               :on-change on-change
               :data-test data-test}
      (for [item options]
        [:option {:key (:value item) :value (:value item)} (:label item)])]

     [:div.input-container
      [:div.main-content
       [:label label]
       [:span.value (:label cvalue "")]]

      [:div.icon
       i/arrow-slide]]]))

(mf/defc submit-button
  [{:keys [label form on-click disabled data-test] :as props}]
  (let [form (or form (mf/use-ctx form-ctx))]
    [:input.btn-primary.btn-large
     {:name "submit"
      :class (when-not (:valid @form) "btn-disabled")
      :disabled (or (not (:valid @form)) (true? disabled))
      :on-click on-click
      :value label
      :data-test data-test
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



(mf/defc multi-input-row
  [{:keys [item, remove-item!, class, invalid-class]}]
  (let [valid (val item)
        text (key item)]
    [:div {:class class}
     [:span.around {:class (when-not valid invalid-class)}
      [:span.text text]
      [:span.icon {:on-click #(remove-item! (key item))} i/cross]]]))

(mf/defc multi-input
  [{:keys [form hint class container-class row-class row-invalid-class] :as props}]
  (let [multi-input-name     (get props :name)
        single-input-name    (keyword (str "single-" (name multi-input-name)))
        single-input-element (dom/get-element (name single-input-name))
        hint-element         (dom/get-element-by-class "hint")
        form                 (or form (mf/use-ctx form-ctx))
        value                (get-in @form [:data multi-input-name] "")
        single-mail-value    (get-in @form [:data single-input-name] "")
        items                (mf/use-state  {})

        comma-items
        (fn [items]
          (if (= "" single-mail-value)
            (str/join "," (keys items))
            (str/join "," (conj (keys items) single-mail-value))))

        update-multi-input
        (fn [all]
          (fm/on-input-change form multi-input-name all true)

          (if (= "" all)
            (do
              (dom/add-class! single-input-element "empty")
              (dom/add-class! hint-element "hidden"))
            (do
              (dom/remove-class! single-input-element "empty")
              (dom/remove-class! hint-element "hidden")))

          (dom/focus! single-input-element))

        remove-item!
        (fn [item]
          (swap! items
                 (fn [items]
                   (let [temp-items (dissoc items item)
                         all (comma-items temp-items)]
                     (update-multi-input all)
                     temp-items))))

        add-item!
        (fn [item valid]
          (swap! items assoc item valid))

        input-key-down (fn [event]
                         (let [target (dom/event->target event)
                               value (dom/get-value target)
                               valid (and (not (= value "")) (dom/valid? target))]

                           (when (kbd/comma? event)
                             (dom/prevent-default event)
                             (add-item! value valid)
                             (fm/on-input-change form single-input-name ""))))

        input-key-up #(update-multi-input (comma-items @items))

        single-props (-> props
                         (dissoc :hint :row-class :row-invalid-class :container-class :class)
                         (assoc
                          :label hint
                          :name single-input-name
                          :on-key-down input-key-down
                          :on-key-up input-key-up
                          :class (str/join " " [class "empty"])))]

    [:div {:class container-class}
     (when (string? hint)
       [:span.hint.hidden hint])
     (for [item @items]
       [:& multi-input-row {:item item
                            :remove-item! remove-item!
                            :class row-class
                            :invalid-class row-invalid-class}])
     [:& input single-props]
     [:input {:id (name multi-input-name)
              :read-only true
              :type "hidden"
              :value value}]]))