;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.fonts-combobox
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.fonts :as fonts]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [font-selector*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Font Picker Inputs -------------------------------------------------------
;;
;; We provide two versions of the font picker: the normal (primitive) input and
;; the composite input. Both allow selecting a font-family either by typing free
;; text or by choosing from the list of fonts loaded in the application. Comma-
;; separated values are treated as literal font-family lists.
;;
;; 1) Normal Font Picker (primitive)
;;    - Used when the token’s value *is itself* a font-family (string) or a
;;      reference to another token.
;;    - The input writes directly to `:value`.
;;    - Validation ensures the string is a valid font-family or a valid token
;;      reference.
;;
;; 2) Composite Font Picker
;;    - Used inside typography tokens, where `:value` is a map (e.g. contains
;;      :font-family, :font-weight, :letter-spacing, etc.).
;;    - The input writes to the specific value-subfield `[:value :font-family]`.
;;    - Only this field is validated and updated—other typography fields remain
;;      untouched.
;;
;; Both modes share the same UI behaviour, but differ in how they store and
;; validate data within the form state.

(defn- resolve-value
  [tokens prev-token token-name value]
  (let [token
        {:value (cto/split-font-family value)
         :name (if (str/blank? token-name)
                 "__PENPOT__TOKEN__NAME__PLACEHOLDER__"
                 token-name)}

        tokens
        (-> tokens
            ;; Remove previous token when renaming a token
            (dissoc (:name prev-token))
            (update (:name token) #(ctob/make-token (merge % prev-token token))))]

    (->> tokens
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

(mf/defc fonts-combobox*
  [{:keys [token tokens name] :rest props}]
  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)

        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        font (fonts/find-font-family value)

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (:value token)]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon i/arrow-down
           :variant "action"
           :type "button"}])

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-select-font
        (mf/use-fn
         (mf/deps font)
         (fn [{:keys [family] :as font}]
           (when (not= value family)
             (fm/on-input-change form input-name family true)
             (rx/push! resolve-stream family))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value false)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props   {:on-change on-change
                                  :value (or value "")
                                  :hint-message (:message hint)
                                  :slot-end font-selector-button
                                  :variant "comfortable"
                                  :hint-type (:type hint)})

        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name touched? token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (when touched?
                                    (if error
                                      (do
                                        (swap! form assoc-in [:extra-errors input-name] {:message error})
                                        (reset! hint* {:message error :type "error"}))
                                      (let [message (tr "workspace.tokens.resolved-value" value)]
                                        (swap! form update :extra-errors dissoc input-name)
                                        (reset! hint* {:message message :type "hint"})))))))]

        (fn []
          (rx/dispose! subs))))

    [:*
     [:> input* props]
     (when font-selector-open?
       [:div {:class (stl/css :font-select-wrapper)}
        [:> font-selector* {:current-font font
                            :on-select on-select-font
                            :on-close on-close-font-selector
                            :full-size true}]])]))

(defn- on-composite-combobox-token-change
  ([form field value]
   (on-composite-combobox-token-change form field value false))
  ([form field value trim?]
   (letfn [(clean-errors [errors]
             (-> errors
                 (dissoc field)
                 (not-empty)))]
     (swap! form (fn [state]
                   (-> state
                       (assoc-in [:data :value field] (if trim? (str/trim value) value))
                       (update :errors clean-errors)
                       (update :extra-errors clean-errors)))))))

(mf/defc composite-fonts-combobox*
  [{:keys [token tokens name] :rest props}]
  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)
        error
        (get-in @form [:errors :value input-name])

        value
        (get-in @form [:data :value input-name] "")

        font (fonts/find-font-family value)

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (get-in token [:value input-name])]
            (rx/behavior-subject value)
            (rx/subject)))


        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon i/arrow-down
           :variant "action"
           :type "button"}])

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-select-font
        (mf/use-fn
         (mf/deps font)
         (fn [{:keys [family] :as font}]
           (when (not= value family)
             (on-composite-combobox-token-change form input-name family true)
             (rx/push! resolve-stream family))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (on-composite-combobox-token-change form input-name value false)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props   {:on-change on-change
                                  :value (or value "")
                                  :hint-message (:message hint)
                                  :slot-end font-selector-button
                                  :variant "comfortable"
                                  :hint-type (:type hint)})

        props
        (if error
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs!
                       (fn [{:keys [error value]}]
                         (cond
                           (and error (str/empty? (:error/value error)))
                           (do
                             (swap! form update-in [:errors :value] dissoc input-name)
                             (swap! form update-in [:data :value] dissoc input-name)
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))


                           (some? error)
                           (let [error' (:message error)]
                             (swap! form assoc-in  [:extra-errors :value input-name] {:message error'})
                             (reset! hint* {:message error' :type "error"}))

                           :else
                           (let [message (tr "workspace.tokens.resolved-value" value)
                                 input-value (get-in @form [:data :value input-name] "")]
                             (swap! form update :errors dissoc :value)
                             (swap! form update :extra-errors dissoc :value)
                             (if (or (empty? value) (= input-value value))
                               (reset! hint* {})
                               (reset! hint* {:message message :type "hint"})))))))]

        (fn []
          (rx/dispose! subs))))

    [:*
     [:> input* props]
     (when font-selector-open?
       [:div {:class (stl/css :font-select-wrapper)}
        [:> font-selector* {:current-font font
                            :on-select on-select-font
                            :on-close on-close-font-selector
                            :full-size true}]])]))