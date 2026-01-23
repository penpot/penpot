;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.input
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.forms :as fc]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;; -----------------------------------------------------------------------------
;; WHY WE HAVE THREE INPUT TYPES: INPUT, COMPOSITE, AND INDEXED
;;
;; Our token editor supports multiple token categories (colors, distances,
;; shadows, typography, etc.). Each category stores its data inside the token’s
;; :value field, but not all tokens have the same internal structure. To keep the
;; UI consistent and predictable, we define three input architectures:
;;
;; 1) INPUTS
;;    ----------------------------------------------------------
;;    Used for tokens where the entire :value is just a single,
;;    atomic field. Examples:
;;      - :distance    → a number or a reference
;;      - :text-case   → one of {none, uppercase, lowercase, capitalize}
;;      - :color       → a single color value or a reference
;;
;;    Characteristics:
;;      * The input writes directly to :value.
;;      * Validation logic is simple because :value is a single unit.
;;      * Switching to "reference mode" simply replaces :value.
;;
;;    Data shape example:
;;      {:value "16px"}
;;      {:value "{spacing.sm}"}
;;
;;
;; 2) COMPOSITE INPUTS
;;    ----------------------------------------------------------
;;    Used when the token contains a set of *named fields* inside :value.
;;    The UI must write into a specific value-subfield inside the :value map.
;;
;;    Example: typography tokens
;;      {:value {:font-family "Inter"
;;               :font-weight 600
;;               :letter-spacing -0.5
;;               :line-height 1.4}}
;;
;;    Why this type exists:
;;      * Each input (font-family, weight, spacing, etc.) maps to a specific
;;        key inside :value.
;;      * The UI must update these fields individually without replacing the
;;        entire value.
;;      * Validation rules apply per-field.
;;
;;    In practice:
;;      - The component knows which value-subfield to update.
;;      - The form accumulates multiple fields into a single map under :value.
;;
;;
;; 3) INDEXED INPUTS
;;    ----------------------------------------------------------
;;    Used for tokens where the :value can be in TWO possible shapes:
;;      A) A direct reference to another token
;;      B) A list (array/vector) of maps describing multiple structured items
;;
;;    Main example: shadow tokens
;;
;;      ;; Option A — reference mode
;;      {:value {:reference "{shadow.soft}"}}
;;
;;      ;; Option B — full definition mode
;;      {:value {:shadow
;;               [{:color "#0003"
;;                 :offset-x 4
;;                 :offset-y 6
;;                 :blur 12
;;                 :spread 0
;;                 :inset? false}
;;
;;                {:color "rgba(0,0,0,0.1)"
;;                 :offset-x 0
;;                 :offset-y 1
;;                 :blur 3
;;                 :spread 0
;;                 :inset? false}]}}
;;
;;    Why this type exists:
;;      * The UI must handle multiple items (indexed layers).
;;      * Each layer has its own internal fields (color, offsets, blur, etc.).
;;      * The user can add/remove layers dynamically.
;;      * Reference mode must disable/remove the structured mode.
;;      * Both shapes are valid, but they cannot coexist at the same time.
;;
;;    Indexed inputs therefore need:
;;      * A tab system ("Shadow" vs "Reference")
;;      * Clear logic to ensure :shadow XOR :reference
;;      * Repetition UI (add/remove layer groups)
;;
;;
;; SUMMARY
;; -----------------------------------------------------------------------------
;; - Plain inputs operate on :value itself.
;; - Composite inputs operate on a map stored under :value, writing into
;;   predefined keys.
;; - Indexed inputs operate on either:
;;       • a reference stored in :value :reference, or
;;       • an array of structured items stored in :value :shadow (or similar),
;;   but never both at the same time.
;;
;; This 3-tiered input system keeps the editor flexible, predictable,
;; and compatible with the many different token types supported by the app.
;; -----------------------------------------------------------------------------

;;
;; Summary:
;; -------
;; - `input*` → single flat value tokens
;; - `input-composite*` → structured tokens with multiple named fields
;;                        (e.g. typography tokens with font-family,
;;                         font-weight, letter-spacing, line-height…)
;; - `input-indexed*` → array-based tokens where each entry is a map
;;                      (e.g. multiple shadow layers)
;;
;; This separation ensures each form input mirrors the actual structure of the
;; token data model, keeping validation and updates correctly scoped.
;; -----------------------------------------------------------------------------


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

(mf/defc input*
  [{:keys [name tokens token] :rest props}]

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

        resolve-stream
        (mf/with-memo [token]
          (if (contains? token :value)
            (rx/behavior-subject (:value token))
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props  props  {:on-change on-change
                                  :default-value value
                                  :variant "comfortable"
                                  :hint-message (:message hint)
                                  :hint-type (:type hint)})
        props
        (if (and error touched?)
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
                      (rx/subs! (fn [{:keys [error value]}]
                                  (let [touched? (get-in @form [:touched input-name])]
                                    (when touched?
                                      (if error
                                        (do
                                          (swap! form assoc-in [:extra-errors input-name] {:message error})
                                          (reset! hint* {:message error :type "error"}))
                                        (let [message (tr "workspace.tokens.resolved-value" value)]
                                          (swap! form update :extra-errors dissoc input-name)
                                          (reset! hint* {:message message :type "hint"}))))))))]

        (fn []
          (rx/dispose! subs))))

    [:> ds/input* props]))

(defn- on-composite-input-change
  ([form field value]
   (on-composite-input-change form field value false))
  ([form field value trim?]
   (letfn [(clean-errors [errors]
             (some-> errors
                     (update :value #(when (map? %) (dissoc % field)))
                     (update :value #(when (seq %) %))
                     (not-empty)))]
     (swap! form (fn [state]
                   (-> state
                       (assoc-in [:data :value field] (if trim? (str/trim value) value))
                       (assoc-in [:touched :value field] true)
                       (update :errors clean-errors)
                       (update :extra-errors clean-errors)))))))

(mf/defc input-composite*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)

        error
        (get-in @form [:errors :value input-name])

        value
        (get-in @form [:data :value input-name] "")

        touched?
        (get-in @form [:touched :value input-name])

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (get-in token [:value input-name])]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (on-composite-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props {:on-change on-change
                                :default-value value
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :hint-type (:type hint)})
        props
        (if (and touched? error)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)

        props (if (and (not error) (= input-name :reference))
                (mf/spread-props props {:hint-formated true})
                props)]

    (mf/with-effect [resolve-stream tokens token input-name name token-name]
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
                             (swap! form update-in [:errors :value] dissoc input-name)
                             (swap! form update-in [:data :value] dissoc input-name)
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))

                           (some? error)
                           (let [error' (:message error)]
                             (swap! form assoc-in  [:extra-errors :value input-name] {:message error'})
                             (reset! hint* {:message error' :type "error"}))

                           :else
                           (let [input-value (get-in @form [:data :value input-name] "")
                                 resolved-value (if (= name :line-height)
                                                  (when-let [{:keys [unit value]} (cft/parse-token-value input-value)]
                                                    (let [font-size (get-in @form [:data :value :font-size] "")
                                                          calculated (case unit
                                                                       "%" (/ (d/parse-double value) 100)
                                                                       "px" (/ (d/parse-double value) (d/parse-double font-size))
                                                                       nil value
                                                                       nil)]
                                                      (dwtf/format-token-value calculated)))
                                                  (dwtf/format-token-value value))
                                 message (tr "workspace.tokens.resolved-value" (or resolved-value value))]
                             (swap! form update :errors dissoc :value)
                             (swap! form update :extra-errors dissoc :value)
                             (swap! form update :async-errors dissoc :reference)
                             (if (= input-value (str resolved-value))
                               (reset! hint* {})
                               (reset! hint* {:message message :type "hint"})))))))]
        (fn []
          (rx/dispose! subs))))

    [:> ds/input* props]))

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

(mf/defc input-indexed*
  [{:keys [name tokens token index value-subfield] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name
        token-name (get-in @form [:data :name] nil)

        error
        (get-in @form [:errors :value value-subfield index input-name])

        value-from-form
        (get-in @form [:data :value value-subfield index input-name] "")

        resolve-stream
        (mf/with-memo [token index input-name]
          (if-let [value (get-in token [:value value-subfield index input-name])]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name index)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (on-indexed-input-change form input-name index value value-subfield true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props {:on-change on-change
                                :value value-from-form
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :hint-type (:type hint)})
        props
        (if error
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)

        props
        (if (and (not error) (= input-name :reference))
          (mf/spread-props props {:hint-formated true})
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
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))

                           (some? error)
                           (let [error' (:message error)]
                             (swap! form assoc-in  [:extra-errors :value value-subfield index input-name] {:message error'})
                             (reset! hint* {:message error' :type "error"}))

                           :else
                           (let [message (tr "workspace.tokens.resolved-value" (dwtf/format-token-value value))
                                 input-value (get-in @form [:data :value value-subfield index input-name] "")]
                             (swap! form update :errors dissoc :value)
                             (swap! form update :extra-errors dissoc :value)
                             (if (= input-value (str value))
                               (reset! hint* {})
                               (reset! hint* {:message message :type "hint"})))))))]
        (fn []
          (rx/dispose! subs))))

    [:> ds/input* props]))
