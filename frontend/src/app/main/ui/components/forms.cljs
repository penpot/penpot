;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.forms
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.components.select :as cs]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cljs.core :as c]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(def form-ctx (mf/create-context nil))
(def use-form fm/use-form)

(mf/defc input
  [{:keys [label help-icon disabled form hint trim children data-testid on-change-value placeholder show-success? show-error]
    :or {show-error true}
    :as props}]
  (let [input-type   (get props :type "text")
        input-name   (get props :name)
        more-classes (get props :class)
        auto-focus?  (get props :auto-focus? false)

        data-testid  (d/nilv data-testid input-name)

        form         (or form (mf/use-ctx form-ctx))

        type'        (mf/use-state input-type)
        focus?       (mf/use-state false)

        is-checkbox? (= @type' "checkbox")
        is-radio?    (= @type' "radio")
        is-text?     (or (= @type' "password")
                         (= @type' "text")
                         (= @type' "email"))
        placeholder  (when is-text? (or placeholder label))

        touched?     (and (contains? (:data @form) input-name)
                          (get-in @form [:touched input-name]))

        error        (get-in @form [:errors input-name])

        value        (get-in @form [:data input-name] "")

        help-icon'   (cond
                       (and (= input-type "password")
                            (= @type' "password"))
                       deprecated-icon/shown

                       (and (= input-type "password")
                            (= @type' "text"))
                       deprecated-icon/hide

                       :else
                       help-icon)

        on-change-value (or on-change-value (constantly nil))

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
                      (fm/on-input-change form input-name value trim)
                      (on-change-value name value)))

        on-blur
        (fn [_]
          (reset! focus? false))

        on-click
        (fn [_]
          (when-not (get-in @form [:touched input-name])
            (swap! form assoc-in [:touched input-name] true)))

        props (-> props
                  (dissoc :help-icon :form :trim :children :show-success? :auto-focus? :label)
                  (assoc :id (name input-name)
                         :value value
                         :auto-focus auto-focus?
                         :on-click (when (or is-radio? is-checkbox?) on-click)
                         :on-focus on-focus
                         :on-blur on-blur
                         :placeholder placeholder
                         :on-change on-change
                         :type @type'
                         :tab-index "0")
                  (cond-> (and value is-checkbox?) (assoc :default-checked value))
                  (cond-> (and touched? (:message error)) (assoc "aria-invalid" "true"
                                                                 "aria-describedby" (dm/str "error-" input-name)))
                  (mfu/map->props))

        checked? (and is-checkbox? (= value true))
        show-valid? (and show-success? touched? (not error))
        show-invalid? (and touched? error)]

    [:div {:class (dm/str more-classes " "
                          (stl/css-case
                           :input-wrapper true
                           :valid         show-valid?
                           :invalid       show-invalid?
                           :checkbox      is-checkbox?
                           :disabled      disabled))}
     [:*
      (cond
        (some? label)
        [:label {:class (stl/css-case :input-with-label-form (not is-checkbox?)
                                      :input-label   is-text?
                                      :radio-label    is-radio?
                                      :checkbox-label is-checkbox?)
                 :tab-index "-1"
                 :for (name input-name)} label

         (when is-checkbox?
           [:span {:class (stl/css-case :global/checked checked?)} (when checked? deprecated-icon/status-tick)])

         (if is-checkbox?
           [:> :input props]

           [:div {:class (stl/css :input-and-icon)}
            [:> :input props]
            (when help-icon'
              [:span {:class (stl/css :help-icon)
                      :on-click (when (= "password" input-type)
                                  swap-text-password)}
               help-icon'])

            (when show-valid?
              [:span {:class (stl/css :valid-icon)}
               deprecated-icon/tick])

            (when show-invalid?
              [:span {:class (stl/css :invalid-icon)}
               deprecated-icon/close])])]

        (some? children)
        [:label {:for (name input-name)}
         [:> :input props]
         children])

      (cond
        (and touched? (:message error) show-error)
        (let [message (:message error)]
          [:div {:id (dm/str "error-" input-name)
                 :class (stl/css :error)
                 :data-testid (dm/str data-testid "-error")}
           message])

        ;; FIXME: DEPRECATED
        (and touched? (:code error) show-error)
        (let [code (:code error)]
          [:div {:id (dm/str "error-" input-name)
                 :class (stl/css :error)
                 :data-testid (dm/str data-testid "-error")}
           (if (vector? code)
             (tr (nth code 0) (i18n/c (nth code 1)))
             (tr code))])

        (string? hint)
        [:div {:class (stl/css :hint)} hint])]]))

(mf/defc textarea
  [{:keys [label disabled form hint trim] :as props}]
  (let [input-name (get props :name)

        form     (or form (mf/use-ctx form-ctx))

        focus?   (mf/use-state false)

        touched? (and (contains? (:data @form) input-name)
                      (get-in @form [:touched input-name]))

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
                  (mfu/map->props))]

    [:div {:class (dm/str klass " " (stl/css :textarea-wrapper))}
     [:label {:class (stl/css :textarea-label)} label]
     [:> :textarea props]
     (cond
       (and touched? (:message error))
       [:span {:class (stl/css :error)} (:message error)]

       (and touched? (:code error))
       [:span {:class (stl/css :error)} (tr (:code error))]

       (string? hint)
       [:span {:class (stl/css :hint)} hint])]))

(mf/defc select
  [{:keys [options disabled form default dropdown-class select-class] :as props
    :or {default ""}}]
  (let [input-name (get props :name)
        form       (or form (mf/use-ctx form-ctx))
        value      (or (get-in @form [:data input-name]) default)

        handle-change
        (fn [event]
          (let [value (if (string? event) event (dom/get-target-val event))]
            (fm/on-input-change form input-name value)))]

    [:div {:class (stl/css :select-wrapper)}
     [:& cs/select
      {:default-value value
       :disabled disabled
       :options options
       :class select-class
       :dropdown-class dropdown-class
       :on-change handle-change}]]))

(mf/defc radio-buttons
  {::mf/wrap-props false}
  [props]
  (let [form          (or (unchecked-get props "form")
                          (mf/use-ctx form-ctx))
        name          (unchecked-get props "name")
        image         (unchecked-get props "image")

        current-value (or (dm/get-in @form [:data name] "")
                          (unchecked-get props "value"))
        on-change     (unchecked-get props "on-change")
        options       (unchecked-get props "options")
        trim?         (unchecked-get props "trim")
        class         (unchecked-get props "class")
        encode-fn     (d/nilv (unchecked-get props "encode-fn") identity)
        decode-fn     (d/nilv (unchecked-get props "decode-fn") identity)

        on-change'
        (mf/use-fn
         (mf/deps on-change form name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value decode-fn)]
             (when (some? form)
               (swap! form assoc-in [:touched name] true)
               (fm/on-input-change form name value trim?))

             (when (fn? on-change)
               (on-change name value)))))]
    [:div {:class (if image
                    class
                    (dm/str class " " (stl/css :custom-radio)))}
     (for [{:keys [image icon value label area]} options]
       (let [image?   (some? image)
             icon?    (some? icon)
             value'   (encode-fn value)
             checked? (= value current-value)
             key      (str/ffmt "%-%" (d/name name) (d/name value'))]
         [:label {:for key
                  :key key
                  :style {:grid-area area}
                  :class (stl/css-case :radio-label true
                                       :global/checked checked?
                                       :with-image (or image? icon?))}
          (cond
            image?
            [:span {:style {:background-image (str/ffmt "url(%)" image)}
                    :class (stl/css :image-inside)}]
            icon?
            [:span {:class (stl/css :icon-inside)} icon]

            :else
            [:span {:class (stl/css-case :radio-icon true
                                         :global/checked checked?)}
             (when checked? [:span {:class (stl/css :radio-dot)}])])

          label
          [:input {:on-change on-change'
                   :type "radio"
                   :class (stl/css :radio-input)
                   :id key
                   :name name
                   :value value'
                   :checked checked?}]]))]))

(mf/defc image-radio-buttons
  {::mf/wrap-props false}
  [props]
  (let [form          (or (unchecked-get props "form")
                          (mf/use-ctx form-ctx))
        name          (unchecked-get props "name")
        image         (unchecked-get props "image")
        img-height    (unchecked-get props "img-height")
        img-width     (unchecked-get props "img-width")
        current-value (or (dm/get-in @form [:data name] "")
                          (unchecked-get props "value"))
        on-change     (unchecked-get props "on-change")
        options       (unchecked-get props "options")
        trim?         (unchecked-get props "trim")
        class         (unchecked-get props "class")
        encode-fn     (d/nilv (unchecked-get props "encode-fn") identity)
        decode-fn     (d/nilv (unchecked-get props "decode-fn") identity)

        on-change'
        (mf/use-fn
         (mf/deps on-change form name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-value decode-fn)]
             (when (some? form)
               (swap! form assoc-in [:touched name] true)
               (fm/on-input-change form name value trim?))

             (when (fn? on-change)
               (on-change name value)))))]

    [:div {:class (if image
                    class
                    (dm/str class " " (stl/css :custom-radio)))}
     (for [{:keys [image icon value label area]} options]
       (let [icon?    (some? icon)
             value'   (encode-fn value)
             checked? (= value current-value)
             key      (str/ffmt "%-%" (d/name name) (d/name value'))]

         [:label {:for key
                  :key key
                  :style {:grid-area area}
                  :class (stl/css-case :radio-label-image true
                                       :global/checked checked?)}
          (cond
            icon?
            [:span {:class (stl/css :icon-inside)
                    :style {:height img-height
                            :width img-width}} icon]

            :else
            [:span {:style {:background-image (str/ffmt "url(%)" image)
                            :height img-height
                            :width img-width}
                    :class (stl/css :image-inside)}])

          [:span {:class (stl/css :image-text)} label]
          [:input {:on-change on-change'
                   :type "radio"
                   :class (stl/css :radio-input)
                   :id key
                   :name name
                   :value value'
                   :checked checked?}]]))]))

(mf/defc submit-button*
  {::mf/wrap-props false}
  [{:keys [on-click children label form class name disabled] :as props}]
  (let [form      (or form (mf/use-ctx form-ctx))

        disabled? (or (and (some? form) (not (:valid @form)))
                      (true? disabled))

        class     (d/nilv class (stl/css :button-submit))

        name      (d/nilv name "submit")

        on-key-down
        (mf/use-fn
         (mf/deps on-click)
         (fn [event]
           (when (and (kbd/enter? event) (fn? on-click))
             (on-click event))))

        props
        (mf/spread-props props {:children mf/undefined
                                :disabled disabled?
                                :on-key-down on-key-down
                                :name name
                                :labek mf/undefined
                                :class class
                                :type "submit"})]

    [:> "button" props
     (if (some? children)
       children
       [:span label])]))

(mf/defc form
  {::mf/wrap-props false}
  [{:keys [on-submit form children class]}]
  (let [on-submit' (mf/use-fn
                    (mf/deps on-submit)
                    (fn [event]
                      (dom/prevent-default event)
                      (when (fn? on-submit)
                        (on-submit form event))))]
    [:& (mf/provider form-ctx) {:value form}
     [:form {:class class :on-submit on-submit'} children]]))

(defn- conj-dedup
  "A helper that adds item into a vector and removes possible
  duplicates. This is not very efficient implementation but is ok for
  handling form input that will have a small number of items."
  [coll item]
  (into [] (distinct) (conj coll item)))

(mf/defc multi-input
  [{:keys [form label class name trim valid-item-fn caution-item-fn on-submit] :as props}]
  (let [form       (or form (mf/use-ctx form-ctx))
        input-name (get props :name)
        touched?   (get-in @form [:touched input-name])
        error      (get-in @form [:errors input-name])
        focus?     (mf/use-state false)

        auto-focus?  (get props :auto-focus? false)

        items      (mf/use-state
                    (fn []
                      (let [initial (get-in @form [:data input-name])]
                        (if (or (vector? initial)
                                (set? initial))
                          (mapv (fn [val]
                                  {:text val
                                   :valid (valid-item-fn val)
                                   :caution (caution-item-fn val)})
                                initial)
                          []))))

        value      (mf/use-state "")
        result     (hooks/use-equal-memo @items)

        empty?     (and (str/empty? @value)
                        (zero? (count @items)))

        klass (str (get props :class) " "
                   (stl/css-case
                    :focus          @focus?
                    :valid          (and touched? (not error))
                    :invalid        (and touched? error)
                    :empty          empty?
                    :custom-multi-input true))

        in-klass (str class " "
                      (stl/css-case
                       :inside-input true
                       :no-padding   (pos? (count @items))
                       :invalid (and (some? valid-item-fn)
                                     touched?
                                     (not (str/empty? @value))
                                     (not (valid-item-fn @value)))))

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
           (let [val (cond-> @value trim str/trim)]
             (cond
               (or (kbd/enter? event) (kbd/comma? event) (kbd/space? event))
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)

                 ;; Once enter/comma is pressed we mark it as touched
                 (swap! form assoc-in [:touched input-name] true)

                 ;; Empty values means "submit" the form (whent some items have been added
                 (when (and (kbd/enter? event) (str/empty? @value) (not-empty @items))
                   (when (fn? on-submit)
                     (on-submit form event)))

                 ;; If we have a string in the input we add it only if valid
                 (when (and (valid-item-fn val) (not (str/empty? @value)))
                   (reset! value "")

                   ;; Once added the form is back as "untouched"
                   (swap! form assoc-in [:touched input-name] false)

                   ;; This split will allow users to copy comma/space separated values of emails
                   (doseq [val (str/split val #",|\s+")]
                     (swap! items conj-dedup {:text (str/trim val)
                                              :valid (valid-item-fn val)
                                              :caution (caution-item-fn val)}))))

               (and (kbd/backspace? event) (str/empty? @value))
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (swap! items (fn [items] (if (c/empty? items) items (pop items)))))))))

        on-blur
        (mf/use-fn
         (fn [_]
           (reset! focus? false)
           (when-not (get-in @form [:touched input-name])
             (swap! form assoc-in [:touched input-name] true))))

        remove-item!
        (mf/use-fn
         (fn [item]
           (swap! items #(into [] (remove (fn [x] (= x item))) %))))

        manage-key-down
        (mf/use-fn
         (fn [item event]
           (when (kbd/enter? event)
             (remove-item! item))))]

    (mf/with-effect [result @value]
      (let [val (cond-> @value trim str/trim)
            values (conj-dedup result {:text val :valid (valid-item-fn val)})
            values (filterv #(:valid %) values)]

        (update-form! values)))

    [:div {:class klass}
     [:input {:id (name input-name)
              :class in-klass
              :type "text"
              :auto-focus auto-focus?
              :on-focus on-focus
              :on-blur on-blur
              :on-key-down on-key-down
              :value @value
              :on-change on-change
              :placeholder (when empty? label)}]
     [:label {:for (name input-name)} label]

     (when-let [items (seq @items)]
       [:div {:class (stl/css :selected-items)}
        (for [item items]
          [:div {:class (stl/css :selected-item)
                 :key (:text item)
                 :tab-index "0"
                 :on-key-down (partial manage-key-down item)}
           [:span {:class (stl/css-case :around true
                                        :invalid (not (:valid item))
                                        :caution (:caution item))}
            [:span {:class (stl/css :text)} (:text item)]
            [:button {:class (stl/css :icon)
                      :on-click #(remove-item! item)} deprecated-icon/close]]])])]))
