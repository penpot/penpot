;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.form-color-input-token
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.color :as cl]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.utilities.swatch :refer [swatch*]]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.core :as c]
   [rumext.v2 :as mf]))

(defn- resolve-value
  [tokens prev-token value]
  (let [token
        {:value value
         :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"}

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

(mf/defc form-color-input-token*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name

        resolved-input-name
        (mf/with-memo [input-name]
          (keyword (str "resolved-" (c/name input-name))))

        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        resolved-value
        (get-in @form [:data resolved-input-name] "")

        hex (if (tinycolor/valid-color resolved-value)
              (tinycolor/->hex-string (tinycolor/valid-color resolved-value))
              "#8f9da3")

        alpha (if (tinycolor/valid-color resolved-value)
                (tinycolor/alpha (tinycolor/valid-color resolved-value))
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
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (if error
                                    (do
                                      (swap! form assoc-in [:errors input-name] {:message error})
                                      (swap! form assoc-in [:errors resolved-input-name] {:message error})
                                      (swap! form update :data dissoc resolved-input-name)
                                      (reset! hint* {:message error :type "error"}))
                                    (let [message (tr "workspace.tokens.resolved-value" value)]
                                      (swap! form update :errors dissoc input-name resolved-input-name)
                                      (swap! form update :data assoc resolved-input-name value)
                                      (reset! hint* {:message message :type "hint"}))))))]

        (fn []
          (rx/dispose! subs))))

    [:*
     [:> input* props]

     (when color-ramp-open?
       [:> ramp* {:color value :on-change on-change-value}])]))