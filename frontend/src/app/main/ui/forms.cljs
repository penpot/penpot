;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.forms
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as k]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def context (mf/create-context nil))

(mf/defc form-input*
  [{:keys [name trim] :rest props}]

  (let [form       (mf/use-ctx context)
        input-name name

        touched?   (and (contains? (:data @form) input-name)
                        (get-in @form [:touched input-name]))

        error      (get-in @form [:errors input-name])
        extra-error (get-in @form [:extra-errors input-name])

        value      (get-in @form [:data input-name] "")

        on-change
        (mf/use-fn
         (mf/deps input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value trim))))

        props
        (mf/spread-props props {:on-change on-change
                                :value value})

        props
        (if (or extra-error (and error touched?))
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message (or error extra-error))})
          props)]

    [:> input* props]))

(defn- conj-dedup
  "Adds item into a vector and removes possible duplicates."
  [coll item]
  (into [] (distinct) (conj coll item)))

(mf/defc form-multi-input*
  [{:keys [name trim valid-item-fn caution-item-fn] :rest props}]
  (let [form       (mf/use-ctx context)

        touched?   (and (contains? (:data @form) name)
                        (get-in @form [:touched name]))

        value      (mf/use-state "")
        focus?     (mf/use-state false)

        items
        (mf/use-state
         (fn []
           (let [initial (get-in @form [:data name])]
             (if (or (vector? initial) (set? initial))
               (mapv (fn [val]
                       {:text val
                        :valid (valid-item-fn val)
                        :caution (caution-item-fn val)})
                     initial)
               []))))

        on-focus
        (mf/use-fn
         #(reset! focus? true))

        on-change
        (mf/use-fn
         (fn [event]
           (let [content (-> event dom/get-target dom/get-input-value)]
             (reset! value content))))

        on-key-down
        (mf/use-fn
         (mf/deps @value form name valid-item-fn caution-item-fn trim)
         (fn [event]
           (let [val (cond-> @value trim str/trim)]
             (cond
               (or (k/enter? event) (k/comma? event) (k/space? event))
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (swap! form assoc-in [:touched name] true)
                 (when (and (valid-item-fn val) (not (str/empty? @value)))
                   (reset! value "")
                   (swap! form assoc-in [:touched name] false)
                   (doseq [v (str/split val #",|\s+")]
                     (let [v (str/trim v)]
                       (swap! items conj-dedup {:text v
                                                :valid (valid-item-fn v)
                                                :caution (caution-item-fn v)})))))

               (and (k/backspace? event) (str/empty? @value))
               (do
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (swap! items (fn [items]
                                (if (empty? items) items (pop items)))))))))

        on-blur
        (mf/use-fn
         (fn []
           (reset! focus? false)
           (when-not (get-in @form [:touched name])
             (swap! form assoc-in [:touched name] true))))

        on-remove-item
        (mf/use-fn
         (fn [item]
           (swap! items #(filterv (fn [x] (not= x item)) %))))

        props
        (mf/spread-props props {:value @value
                                :on-change on-change
                                :on-focus on-focus
                                :on-blur on-blur
                                :on-key-down on-key-down
                                :hint-type (when (and touched?
                                                      (not (str/empty? @value))
                                                      (not (valid-item-fn @value))) "error")})]

    ;; Sync form data whenever items or input value changes.
    ;; This ensures the current (unconfirmed) input value is included
    ;; in the form data when the user submits without pressing Enter.
    (mf/with-effect [@items @value]
      (let [items-text (mapv :text @items)
            val        (cond-> @value trim str/trim)
            combined   (if (and (valid-item-fn val) (not (str/empty? val)))
                         (conj items-text val)
                         items-text)
            data       (str/join " " combined)]
        (fm/update-input-value! form name data)))

    [:div {:class (stl/css :multi-input)}
     [:> input* props]

     (when-let [items-seq (seq @items)]
       [:div {:class (stl/css :multi-input-chips)}
        (for [item items-seq]
          [:div {:class (stl/css :multi-input-chip)
                 :key (:text item)}
           [:span {:class (stl/css :multi-input-chip-text)}
            (:text item)]
           [:> icon-button* {:variant "ghost"
                             :class (stl/css :multi-input-chip-icon)
                             :icon i/close
                             :icon-size "s"
                             :aria-label (tr "labels.remove")
                             :on-click (partial on-remove-item item)}]])])]))

(mf/defc form-select*
  [{:keys [name] :rest props}]
  (let [select-name name
        form        (mf/use-ctx context)
        value       (get-in @form [:data select-name] "")

        handle-change
        (fn [event]
          (let [value (if (string? event) event (dom/get-target-val event))]
            (fm/on-input-change form select-name value)))

        props
        (mf/spread-props props {:on-change handle-change
                                :value value})]

    [:> select* props]))

(mf/defc form-submit*
  [{:keys [disabled on-submit] :rest props}]

  (let [form      (mf/use-ctx context)
        form-state (when form @form)

        disabled? (mf/use-memo
                   (mf/deps form form-state disabled)
                   (fn []
                     (boolean
                      (or (nil? form)
                          (true? disabled)
                          (not (:valid form-state))
                          (seq (:async-errors form-state))
                          (seq (:extra-errors form-state))))))

        handle-key-down-save
        (mf/use-fn
         (mf/deps on-submit form disabled?)
         (fn [e]
           (when (and (or (k/enter? e) (k/space? e)) (not disabled?))
             (dom/prevent-default e)
             (on-submit form e))))

        props
        (mf/spread-props props {:on-key-down handle-key-down-save
                                :type "submit"})

        props
        (if disabled?
          (mf/spread-props props {:disabled true
                                  :on-key-down handle-key-down-save
                                  :type "submit"})
          props)]

    [:> button* props]))

(mf/defc form*
  [{:keys [on-submit form children class]}]
  (let [on-submit' (mf/use-fn
                    (mf/deps on-submit)
                    (fn [event]
                      (dom/prevent-default event)
                      (when (fn? on-submit)
                        (on-submit form event))))]
    [:> (mf/provider context) {:value form}
     [:form {:class class :on-submit on-submit'} children]]))
