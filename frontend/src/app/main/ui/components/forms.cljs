;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.forms
  (:require
   [app.common.data :as d]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [cljs.core :as c]
   [clojure.string]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

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
                    (let [value (-> event dom/get-target dom/get-input-value)]
                      (swap! form assoc-in [:touched input-name] true)
                      (fm/on-input-change form input-name value trim)))

        on-blur
        (fn [_]
          (reset! focus? false)
          (when-not (get-in @form [:touched input-name])
            (swap! form assoc-in [:touched input-name] true)))

        on-click
        (fn [_]
          (when-not (get-in @form [:touched input-name])
            (swap! form assoc-in [:touched input-name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim :children)
                  (assoc :id (name input-name)
                         :value value
                         :auto-focus auto-focus?
                         :on-click (when (or is-radio? is-checkbox?) on-click)
                         :on-focus on-focus
                         :on-blur on-blur
                         :placeholder label
                         :on-change on-change
                         :type @type')
                (cond-> (and value is-checkbox?) (assoc :default-checked value))
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
                  :disabled  disabled)
                  ;; :empty     (str/empty? value)


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
      :class (when (or (not (:valid @form)) (true? disabled)) "btn-disabled")
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

(defn- conj-dedup
  "A helper that adds item into a vector and removes possible
  duplicates. This is not very efficient implementation but is ok for
  handling form input that will have a small number of items."
  [coll item]
  (into [] (distinct) (conj coll item)))

(mf/defc multi-input
  [{:keys [form label class name trim valid-item-fn on-submit] :as props}]
  (let [form       (or form (mf/use-ctx form-ctx))
        input-name (get props :name)
        touched?   (get-in @form [:touched input-name])
        error      (get-in @form [:errors input-name])
        focus?     (mf/use-state false)

        items      (mf/use-state [])
        value      (mf/use-state "")
        result     (hooks/use-equal-memo @items)

        empty?     (and (str/empty? @value)
                        (zero? (count @items)))

        klass      (str (get props :class) " "
                        (dom/classnames
                         :focus          @focus?
                         :valid          (and touched? (not error))
                         :invalid        (and touched? error)
                         :empty          empty?
                         :custom-multi-input true
                         :custom-input   true))

        in-klass  (str class " "
                       (dom/classnames
                        :no-padding (pos? (count @items))))

        on-focus
        (mf/use-fn #(reset! focus? true))

        on-change
        (mf/use-fn
         (fn [event]
           (let [content (-> event dom/get-target dom/get-input-value)]
             (reset! value content))))

        update-form!
        (mf/use-fn
         (mf/deps form)
         (fn [items]
           (let [value (str/join " " (map :text items))]
             (fm/update-input-value! form input-name value))))

        on-key-down
        (mf/use-fn
         (mf/deps @value)
         (fn [event]
           (cond
             (or (kbd/enter? event)
                 (kbd/comma? event))
             (do
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (let [val (cond-> @value trim str/trim)]
                 (when (and (kbd/enter? event) (str/empty? @value) (not-empty @items))
                   (on-submit form))
                 (when (not (str/empty? @value))
                   (reset! value "")
                   (swap! items conj-dedup {:text val :valid (valid-item-fn val)}))))

             (and (kbd/backspace? event)
                  (str/empty? @value))
             (do
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (swap! items (fn [items] (if (c/empty? items) items (pop items))))))))

        on-blur
        (mf/use-fn
         (fn [_]
           (reset! focus? false)
           (when-not (get-in @form [:touched input-name])
             (swap! form assoc-in [:touched input-name] true))))

        remove-item!
        (mf/use-fn
         (fn [item]
           (swap! items #(into [] (remove (fn [x] (= x item))) %))))]

    (mf/with-effect [result @value]
      (let [val (cond-> @value trim str/trim)
            values (conj-dedup result {:text val :valid (valid-item-fn val)})
            values (filterv #(:valid %) values)]
        (update-form! values)))

    [:div {:class klass}
     (when-let [items (seq @items)]
       [:div.selected-items
        (for [item items]
          [:div.selected-item {:key (:text item)}
           [:span.around {:class (when-not (:valid item) "invalid")}
            [:span.text (:text item)]
            [:span.icon {:on-click #(remove-item! item)} i/cross]]])])

     [:input {:id (name input-name)
              :class in-klass
              :type "text"
              :auto-focus true
              :on-focus on-focus
              :on-blur on-blur
              :on-key-down on-key-down
              :value @value
              :on-change on-change
              :placeholder (when empty? label)}]
     [:label {:for (name input-name)} label]]))
