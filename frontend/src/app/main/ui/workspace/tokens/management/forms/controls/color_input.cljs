;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.color-input
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as color]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as cl]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.refs :as refs]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; --- Color Inputs -------------------------------------------------------------
;;
;; The color input exists in two variants: the normal (primitive) input and the
;; indexed input. Both support hex, rgb(), hsl(), named colors, opacity changes,
;; color-picker selection, and token references.
;;
;; 1) Normal Color Input (primitive)
;;    - Used when the token’s `:value` *is a single color* or a reference.
;;    - Writes directly to `:value`.
;;    - Validation ensures the color is in an accepted format or is a valid
;;      color token reference.

;; 2) Indexed Color Input
;;    - Used when the token’s value stores an array of items (e.g. inside
;;      shadows, where each shadow layer has its own :color field).
;;    - The input writes to a nested value-subfield:
;;         [:value <value-subfield> <index> :color]
;;    - Only that specific color entry is validated.
;;    - Other properties (offsets, blur, inset, etc.) remain untouched.
;;
;; Both variants provide identical color-picker and text-input behavior, but
;; differ in how they persist the value within the form’s nested structure.


(defn- resolve-value
  [tokens prev-token token-name value]
  (let [token
        {:value value
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

(defn- hex->color-obj
  [hex]
  (when-let [tc (tinycolor/valid-color hex)]
    (let [hex (tinycolor/->hex-string tc)
          alpha (tinycolor/alpha tc)
          [r g b] (cl/hex->rgb hex)
          [h s v] (cl/hex->hsv hex)]
      {:hex hex
       :r r :g g :b b
       :h h :s s :v v
       :alpha alpha})))

(mf/defc ramp*
  [{:keys [color on-change]}]
  (let [wrapper-node-ref (mf/use-ref nil)
        dragging-ref     (mf/use-ref false)

        on-start-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref true))

        on-finish-drag
        (mf/use-fn #(mf/set-ref-val! dragging-ref false))

        internal-color*
        (mf/use-state #(hex->color-obj color))

        internal-color
        (deref internal-color*)

        on-change'
        (mf/use-fn
         (mf/deps on-change)
         (fn [{:keys [hex alpha] :as selector-color}]
           (let [dragging? (mf/ref-val dragging-ref)]
             (when-not (and dragging? hex)
               (reset! internal-color* selector-color)
               (on-change hex alpha)))))]
    (mf/use-effect
     (mf/deps color)
     (fn []
       ;; Update internal color when user changes input value
       (when-let [color (tinycolor/valid-color color)]
         (when-not (= (tinycolor/->hex-string color) (:hex internal-color))
           (reset! internal-color* (hex->color-obj color))))))

    (colorpicker/use-color-picker-css-variables! wrapper-node-ref internal-color)
    [:div {:ref wrapper-node-ref}
     [:> ramp-selector*
      {:color internal-color
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag
       :on-change on-change'}]]))

(mf/defc color-input*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)


        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        extra-error
        (get-in @form [:extra-errors input-name])

        value
        (get-in @form [:data input-name] "")

        color-resolved
        (get-in @form [:data :color-result] "")


        valid-color (or (tinycolor/valid-color value)
                        (tinycolor/valid-color color-resolved))

        profile     (mf/deref refs/profile)

        default-bullet-color
        (case (:theme profile)
          "light"
          color/background-quaternary-light
          color/background-quaternary)
        hex
        (if valid-color
          (tinycolor/->hex-string (tinycolor/valid-color valid-color))
          default-bullet-color)

        alpha
        (if (tinycolor/valid-color valid-color)
          (tinycolor/alpha (tinycolor/valid-color valid-color))
          1)

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (:value token)]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        color-ramp-open* (mf/use-state false)
        color-ramp-open? (deref color-ramp-open*)

        on-click-swatch
        (mf/use-fn
         (mf/deps color-ramp-open?)
         (fn []
           (let [open? (not color-ramp-open?)]
             (reset! color-ramp-open* open?))))

        swatch
        (mf/html
         [:> swatch*
          {:background {:color hex :opacity alpha}
           :show-tooltip false
           :data-testid "token-form-color-bullet"
           :class (stl/css :slot-start)
           :on-click on-click-swatch}])

        on-change-value
        (mf/use-fn
         (mf/deps resolve-stream input-name value)
         (fn [hex alpha]
           (let [;; StyleDictionary will always convert to hex/rgba, so we take the format from the value input field
                 prev-input-color (some-> value
                                          (tinycolor/valid-color))
                 ;; If the input is a reference we will take the format from the computed value
                 prev-computed-color (when-not prev-input-color
                                       (some-> value (tinycolor/valid-color)))
                 prev-format (some-> (or prev-input-color prev-computed-color)
                                     (tinycolor/color-format))
                 to-rgba? (and
                           (< alpha 1)
                           (or (= prev-format "hex") (not prev-format)))
                 to-hex? (and (not prev-format) (= alpha 1))
                 format (cond
                          to-rgba? "rgba"
                          to-hex? "hex"
                          prev-format prev-format
                          :else "hex")
                 color-value (-> (tinycolor/valid-color hex)
                                 (tinycolor/set-alpha (or alpha 1))
                                 (tinycolor/->string format))]
             (when (not= value color-value)
               (fm/on-input-change form input-name color-value true)
               (rx/push! resolve-stream color-value)))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [raw-value (-> event dom/get-target dom/get-input-value)
                 value (if (tinycolor/hex-without-hash-prefix? raw-value)
                         (dm/str "#" raw-value)
                         raw-value)]
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props   {:on-change on-change
                                  ;; TODO: Review this value vs default-value
                                  :value (or value "")
                                  :hint-message (:message hint)
                                  :variant "comfortable"
                                  :slot-start swatch
                                  :hint-type (:type hint)})

        props
        (cond
          (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          (and extra-error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message extra-error)})
          :else
          props)]

    (mf/with-effect [resolve-stream tokens token input-name token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (let [touched? (get-in @form [:touched input-name])]
                                    (when touched?
                                      (if error
                                        (do
                                          (swap! form assoc-in [:extra-errors input-name] {:message error})
                                          (swap! form assoc-in [:data :color-result] "")
                                          (reset! hint* {:message error :type "error"}))
                                        (let [message (tr "workspace.tokens.resolved-value" (dwtf/format-token-value value))]
                                          (swap! form update :extra-errors dissoc input-name)
                                          (swap! form assoc-in [:data :color-result] value)
                                          (reset! hint* {:message message :type "hint"}))))))))]

        (fn []
          (rx/dispose! subs))))

    [:*
     [:> ds/input* props]

     (when color-ramp-open?
       [:> ramp* {:color value :on-change on-change-value}])]))

(defn- on-indexed-input-change
  ([form field index value value-subfield]
   (on-indexed-input-change form field index value value-subfield false))
  ([form field index value value-subfield trim?]
   (letfn [(clean-errors [errors]
             (-> errors
                 (dissoc field)
                 (not-empty)))]
     (swap! form (fn [state]
                   (-> state
                       (assoc-in [:data :value value-subfield index field] (if trim? (str/trim value) value))
                       (update :errors clean-errors)
                       (update :extra-errors clean-errors)))))))

(mf/defc indexed-color-input*
  [{:keys [name tokens token index value-subfield] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)
        error
        (get-in @form [:errors :value value-subfield index input-name])

        value
        (get-in @form [:data :value value-subfield index input-name] "")

        color-resolved
        (get-in @form [:data :value value-subfield index :color-result] "")

        valid-color (or (tinycolor/valid-color value)
                        (tinycolor/valid-color color-resolved))
        profile     (mf/deref refs/profile)

        default-bullet-color
        (case (:theme profile)
          "light"
          color/background-quaternary-light
          color/background-quaternary)

        hex
        (if valid-color
          (tinycolor/->hex-string (tinycolor/valid-color valid-color))
          default-bullet-color)

        alpha
        (if (tinycolor/valid-color valid-color)
          (tinycolor/alpha (tinycolor/valid-color valid-color))
          1)

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (get-in token [:value value-subfield index input-name])]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        color-ramp-open* (mf/use-state false)
        color-ramp-open? (deref color-ramp-open*)

        on-click-swatch
        (mf/use-fn
         (mf/deps color-ramp-open?)
         (fn []
           (let [open? (not color-ramp-open?)]
             (reset! color-ramp-open* open?))))

        swatch
        (mf/html
         [:> swatch*
          {:background {:color hex :opacity alpha}
           :show-tooltip false
           :data-testid "token-form-color-bullet"
           :class (stl/css :slot-start)
           :on-click on-click-swatch}])

        on-change-value
        (mf/use-fn
         (mf/deps resolve-stream input-name value index)
         (fn [hex alpha]
           (let [;; StyleDictionary will always convert to hex/rgba, so we take the format from the value input field
                 prev-input-color (some-> value
                                          (tinycolor/valid-color))
                 ;; If the input is a reference we will take the format from the computed value
                 prev-computed-color (when-not prev-input-color
                                       (some-> value (tinycolor/valid-color)))
                 prev-format (some-> (or prev-input-color prev-computed-color)
                                     (tinycolor/color-format))
                 to-rgba? (and
                           (< alpha 1)
                           (or (= prev-format "hex") (not prev-format)))
                 to-hex? (and (not prev-format) (= alpha 1))
                 format (cond
                          to-rgba? "rgba"
                          to-hex? "hex"
                          prev-format prev-format
                          :else "hex")
                 color-value (-> (tinycolor/valid-color hex)
                                 (tinycolor/set-alpha (or alpha 1))
                                 (tinycolor/->string format))]
             (when (not= value color-value)
               (on-indexed-input-change form input-name index color-value value-subfield true)
               (rx/push! resolve-stream color-value)))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name index)
         (fn [event]
           (let [raw-value (-> event dom/get-target dom/get-input-value)
                 value (if (tinycolor/hex-without-hash-prefix? raw-value)
                         (dm/str "#" raw-value)
                         raw-value)]
             (on-indexed-input-change form input-name index value value-subfield true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props   {:on-change on-change
                                  :value (or value "")
                                  :hint-message (:message hint)
                                  :slot-start swatch
                                  :hint-type (:type hint)})

        props
        (if error
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name index value-subfield token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 (assoc error :message ((:error/fn error) (:error/value error)))))))

                      (rx/subs!
                       (fn [{:keys [error value]}]
                         (cond
                           (and error (str/empty? (:error/value error)))
                           (do
                             (swap! form update-in [:errors :value value-subfield index] dissoc input-name)
                             (swap! form update-in [:data :value value-subfield index] dissoc input-name)
                             (swap! form assoc-in [:data :value value-subfield index :color-result] "")
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))

                           (some? error)
                           (let [error' (:message error)]
                             (do
                               (swap! form assoc-in  [:extra-errors :value value-subfield index input-name] {:message error'})
                               (swap! form assoc-in [:data :value value-subfield index :color-result] "")
                               (reset! hint* {:message error' :type "error"})))

                           :else
                           (let [message (tr "workspace.tokens.resolved-value" (dwtf/format-token-value value))
                                 input-value (get-in @form [:data :value value-subfield index input-name] "")]
                             (do
                               (swap! form update :errors dissoc :value)
                               (swap! form update :extra-errors dissoc :value)
                               (swap! form assoc-in [:data :value value-subfield index :color-result] (dwtf/format-token-value value))
                               (if (= input-value (str value))
                                 (reset! hint* {})
                                 (reset! hint* {:message message :type "hint"}))))))))]
        (fn []
          (rx/dispose! subs))))

    [:*
     [:> ds/input* props]

     (when color-ramp-open?
       [:> ramp* {:color value :on-change on-change-value}])]))
