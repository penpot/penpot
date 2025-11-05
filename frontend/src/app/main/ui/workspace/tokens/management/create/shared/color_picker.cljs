(ns app.main.ui.workspace.tokens.management.create.shared.color-picker
  (:require
   [app.common.types.color :as c]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.ui.workspace.colorpicker :as colorpicker]
   [app.main.ui.workspace.colorpicker.ramp :refer [ramp-selector*]]
   [app.main.ui.workspace.tokens.management.create.input-token-color-bullet :refer [input-token-color-bullet*]]
   [app.main.ui.workspace.tokens.management.create.input-tokens-value :refer [input-token* token-value-hint*]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

;; FIXME: this function has confusing name
(defn- hex->value
  [hex]
  (when-let [tc (tinycolor/valid-color hex)]
    (let [hex (tinycolor/->hex-string tc)
          alpha (tinycolor/alpha tc)
          [r g b] (c/hex->rgb hex)
          [h s v] (c/hex->hsv hex)]
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
        (mf/use-state #(hex->value color))

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
           (reset! internal-color* (hex->value color))))))

    (colorpicker/use-color-picker-css-variables! wrapper-node-ref internal-color)
    [:div {:ref wrapper-node-ref}
     [:> ramp-selector*
      {:color internal-color
       :on-start-drag on-start-drag
       :on-finish-drag on-finish-drag
       :on-change on-change'}]]))

(mf/defc color-picker*
  [{:keys [placeholder label default-value input-ref on-blur on-update-value on-external-update-value custom-input-token-value-props token-resolve-result]}]
  (let [{:keys [color on-display-colorpicker]} custom-input-token-value-props
        color-ramp-open* (mf/use-state false)
        color-ramp-open? (deref color-ramp-open*)

        on-click-swatch
        (mf/use-fn
         (mf/deps color-ramp-open? on-display-colorpicker)
         (fn []
           (let [open? (not color-ramp-open?)]
             (reset! color-ramp-open* open?)
             (when on-display-colorpicker
               (on-display-colorpicker open?)))))

        swatch
        (mf/html
         [:> input-token-color-bullet*
          {:color color
           :on-click on-click-swatch}])

        on-change'
        (mf/use-fn
         (mf/deps color on-external-update-value)
         (fn [hex-value alpha]
           (let [;; StyleDictionary will always convert to hex/rgba, so we take the format from the value input field
                 prev-input-color (some-> (dom/get-value (mf/ref-val input-ref))
                                          (tinycolor/valid-color))
                                                                                  ;; If the input is a reference we will take the format from the computed value
                 prev-computed-color (when-not prev-input-color
                                       (some-> color (tinycolor/valid-color)))
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
                 color-value (-> (tinycolor/valid-color hex-value)
                                 (tinycolor/set-alpha (or alpha 1))
                                 (tinycolor/->string format))]
             (dom/set-value! (mf/ref-val input-ref) color-value)
             (on-external-update-value color-value))))]

    [:*
     [:> input-token*
      {:placeholder placeholder
       :label label
       :default-value default-value
       :ref input-ref
       :on-blur on-blur
       :on-change on-update-value
       :slot-start swatch}]
     (when color-ramp-open?
       [:> ramp*
        {:color (some-> color (tinycolor/valid-color))
         :on-change on-change'}])
     [:> token-value-hint* {:result token-resolve-result}]]))