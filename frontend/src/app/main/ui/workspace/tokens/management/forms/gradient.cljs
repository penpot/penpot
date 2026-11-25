;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.management.forms.gradient
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.tinycolor :as tinycolor]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.gradient-form :as wtgf]
   [app.main.ui.components.numeric-input :as deprecated-input]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.main.ui.forms :as forms]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.colorpicker.gradients :refer [gradients*]]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.main.ui.workspace.tokens.management.forms.validators :refer [default-validate-token]]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))
(defn- check-gradient-token-self-reference
  [{:keys [name value]}]
  (when (cto/references-token? value name)
    (wte/get-error-code :error.token/direct-self-reference)))

(defn- check-empty-gradient-token
  [{:keys [value]}]
  (when-not (and (>= (count (:stops value)) 2)
                 (every? wtgf/complete-stop? (:stops value)))
    (wte/get-error-code :error.token/empty-input)))

(defn- validate-gradient-token
  [{:keys [token-value] :as params}]
  (cond
    ;; Entering form without a value - show no error just resolve nil
    (nil? token-value) (rx/of nil)
    ;; Validate reference string
    (cto/composite-token-reference? token-value) (default-validate-token params)
    ;; Validate composite token
    :else
    (-> params
        (update :token-value wtgf/form-value->token-value)
        (assoc :validators [check-empty-gradient-token
                            check-gradient-token-self-reference])
        (default-validate-token))))

;; What the fields of a stop need besides what the gradient editor
;; gives them.
(def ^:private stop-fields-context
  (mf/create-context nil))

(mf/defc stop-fields*
  "Shows the fields of a stop in the gradient editor: its position, its
  color and the opacity of the color. The position and the color take
  references."
  {::mf/private true}
  [{:keys [stop index is-selected on-select-stop on-remove-stop]}]
  (let [{:keys [token tokens ramp-container on-change-opacity is-removable]}
        (mf/use-ctx stop-fields-context)

        form           (mf/use-ctx forms/context)
        color          (get-in @form [:data :value :stops index :color])
        position       (get-in @form [:data :value :stops index :position])

        color-token    (wtgf/stop-token (:value token) index :color)
        color-token    (hooks/use-equal-memo color-token)

        position-token (wtgf/stop-token (:value token) index :position)
        position-token (hooks/use-equal-memo position-token)

        ;; A referenced color keeps its own opacity
        plain-color?   (some? (tinycolor/valid-color color))

        on-focus
        (mf/use-fn
         (mf/deps index on-select-stop)
         (fn [_]
           (on-select-stop index)))

        on-remove
        (mf/use-fn
         (mf/deps index on-remove-stop)
         (fn [_]
           (on-remove-stop index)))

        on-opacity-change
        (mf/use-fn
         (mf/deps index on-change-opacity)
         (fn [value]
           (on-change-opacity index (/ value 100))))]

    [:div {:class (stl/css :stop-fields)
           :on-focus on-focus
           :data-testid (str "gradient-stop-fields-" index)}
     [:div {:class (stl/css :position-input-wrapper)}
      [:> token.controls/input-indexed*
       {:aria-label (tr "workspace.tokens.gradient-position")
        :name :position
        :text-icon "%"
        ;; A reference or an expression shows what it resolves to
        :is-resolved-hint-hidden (wtgf/position-hint-hidden? position)
        :format-resolved dwtf/format-position
        :is-tokenscript true
        :token position-token
        :index index
        :value-subfield :stops
        :tokens tokens}]]

     [:div {:class (stl/css :color-input-wrapper)}
      [:> token.controls/indexed-color-input*
       {:placeholder (tr "workspace.tokens.token-value-enter")
        :aria-label (tr "workspace.tokens.color")
        :name :color
        :is-resolved-hint-hidden true
        :token color-token
        :value-subfield :stops
        :index index
        :tokens tokens
        :is-ramp-open (and is-selected (some? ramp-container))
        :ramp-container ramp-container
        :on-swatch-click on-focus
        :slot-end
        (mf/html
         [:div {:class (stl/css :opacity-element-wrapper)}
          [:span {:class (stl/css :icon-text)} "%"]
          [:> deprecated-input/numeric-input*
           {:value (if (some? (:opacity stop))
                     (str (mth/round (* 100 (:opacity stop))))
                     "")
            :class (stl/css :opacity-input)
            :aria-label (tr "workspace.options.opacity")
            :placeholder "--"
            :is-disabled (not plain-color?)
            :on-change on-opacity-change
            :default 100
            :min 0
            :max 100}]])}]]

     [:> icon-button* {:variant "ghost"
                       :type "button"
                       :aria-label (tr "workspace.tokens.gradient-remove-stop")
                       :disabled (not is-removable)
                       :on-click on-remove
                       :icon i/remove}]]))

(defn- noop [])

(mf/defc composite-form*
  {::mf/private true}
  [{:keys [token tokens]}]
  (let [form   (mf/use-ctx forms/context)
        name   (get-in @form [:data :name])
        value  (get-in @form [:data :value])
        kind   (:kind value)
        stops  (:stops value)

        ;; The colors and positions of the stops may be references, so
        ;; the strip shows the gradient as it resolves.
        resolved* (mf/use-state nil)
        resolved  (deref resolved*)

        ;; The selected stop by its id, so the selection follows the
        ;; stop when stops are added, moved or removed
        selected-id* (mf/use-state nil)
        selected     (wtgf/selected-index stops (deref selected-id*))

        ;; The angle input keeps its own text, so it is rendered anew
        ;; to show an angle the rotate button set.
        rotations* (mf/use-state 0)
        rotations  (deref rotations*)

        ;; Where the color ramp of the selected stop goes
        ramp-container* (mf/use-state nil)
        ramp-container  (deref ramp-container*)

        markers
        (mf/with-memo [stops resolved]
          (wtgf/marker-stops stops resolved))

        stops-for-editor
        (mf/with-memo [stops markers]
          (wtgf/editor-stops stops markers))

        angle-token
        (mf/with-memo [token]
          (wtgf/angle-token (:value token)))

        update-value
        (mf/use-fn
         (mf/deps form)
         (fn [f & args]
           (swap! form (fn [state]
                         (-> (apply update-in state [:data :value] f args)
                             (update :extra-errors dissoc :value ""))))))

        on-change-type
        (mf/use-fn
         (mf/deps update-value)
         (fn [type]
           (update-value assoc :kind (if (= :radial-gradient type) "radial" "linear"))))

        on-select-stop
        (mf/use-fn
         (mf/deps form)
         (fn [index]
           (reset! selected-id* (wtgf/stop-id-at (get-in @form [:data :value :stops]) index))))

        ;; As in the color picker, a new stop is the selected one
        select-next-stop
        (mf/use-fn
         (mf/deps form)
         (fn []
           (reset! selected-id* (wtgf/next-stop-id (get-in @form [:data :value :stops])))))

        on-move-stop
        (mf/use-fn
         (mf/deps update-value)
         (fn [_ stop]
           (update-value wtgf/move-stop (:index stop) (:offset stop))))

        on-add-stop
        (mf/use-fn
         (mf/deps update-value markers select-next-stop)
         (fn [offset]
           (select-next-stop)
           (update-value wtgf/add-stop markers offset)))

        on-add-stop-auto
        (mf/use-fn
         (mf/deps update-value markers select-next-stop)
         (fn []
           (select-next-stop)
           (update-value wtgf/add-stop-auto markers)))

        on-reverse-stops
        (mf/use-fn
         (mf/deps update-value)
         (fn []
           (update-value wtgf/reverse-stops)))

        on-reorder-stops
        (mf/use-fn
         (mf/deps update-value)
         (fn [from to]
           (update-value wtgf/reorder-stops from to)))

        on-change-opacity
        (mf/use-fn
         (mf/deps update-value)
         (fn [index opacity]
           (update-value wtgf/set-stop-opacity index opacity)))

        on-rotate
        (mf/use-fn
         (mf/deps update-value)
         (fn []
           (update-value update :angle wtgf/rotate-angle)
           (swap! rotations* inc)))

        on-remove-stop
        (mf/use-fn
         (mf/deps update-value)
         (fn [index]
           (update-value update :stops d/remove-at-index index)))

        set-ramp-container
        (mf/use-fn
         (fn [node]
           (reset! ramp-container* node)))

        is-removable (> (count stops) 2)

        stop-fields-data
        (mf/with-memo [token tokens ramp-container on-change-opacity is-removable]
          {:token token
           :tokens tokens
           :ramp-container ramp-container
           :on-change-opacity on-change-opacity
           :is-removable is-removable})]

    (mf/with-effect [name value token tokens]
      (let [sub (->> (rx/timer 300)
                     (rx/mapcat (fn [_]
                                  (validate-gradient-token {:token-value value
                                                            :token-name name
                                                            :prev-token token
                                                            :tokens tokens})))
                     (rx/subs!
                      (fn [resolved]
                        (let [resolved (:resolved-value resolved)
                              stops'   (wtgf/with-resolved-colors stops resolved)]
                          (reset! resolved* resolved)
                          (when (not= stops stops')
                            (swap! form assoc-in [:data :value :stops] stops'))))
                      (fn [_]
                        (reset! resolved* nil))))]
        (fn []
          (rx/dispose! sub))))

    [:*
     (when (= "linear" kind)
       [:div {:class (stl/css :input-row)}
        [:> token.controls/input-composite*
         {:key rotations
          :aria-label (tr "workspace.tokens.gradient-angle")
          :placeholder (tr "workspace.tokens.gradient-angle")
          :name :angle
          :icon i/rotation
          :is-tokenscript true
          :format-resolved dwtf/format-angle
          :token angle-token
          :tokens tokens}]])

     ;; The gradient editor of the color picker, with fields for the
     ;; stops that take references
     [:div {:class (stl/css :gradient-editor)}
      [(mf/provider stop-fields-context) {:value stop-fields-data}
       [:> gradients* {:type (if (= "radial" kind) :radial-gradient :linear-gradient)
                       :stops stops-for-editor
                       :editing-stop selected
                       :stop-fields stop-fields*
                       ;; A reference or an expression can't be turned
                       :is-rotate-disabled (or (not= "linear" kind)
                                               (not (wtgf/rotatable-angle? (:angle value))))
                       :on-select-stop on-select-stop
                       :on-change-type on-change-type
                       :on-change-stop on-move-stop
                       :on-add-stop-preview on-add-stop
                       :on-add-stop-auto on-add-stop-auto
                       :on-remove-stop on-remove-stop
                       :on-stop-edit-start noop
                       :on-stop-edit-finish noop
                       :on-reverse-stops on-reverse-stops
                       :on-rotate-stops on-rotate
                       :on-reorder-stops on-reorder-stops}]]]

     ;; The color ramp of the selected stop
     [:div {:ref set-ramp-container}]]))

(mf/defc reference-form*
  "Edits the reference to a gradient token, or tokenscript that resolves
  to a gradient. The form converts a pasted CSS `linear-gradient()` to
  tokenscript. The user can edit a tokenscript `linear-gradient()` in
  the gradient editor."
  {::mf/private true}
  [{:keys [token tokens handle-toggle]}]
  (let [form      (mf/use-ctx forms/context)
        reference (get-in @form [:data :value :reference])

        ;; What the last paste was converted to, as long as it is in the field
        converted* (mf/use-state nil)
        converted  (deref converted*)
        converted? (and (some? converted)
                        (some? reference)
                        (str/includes? reference converted))

        ;; The gradient the text of the field is, as the form edits it:
        ;; the field can be typed in, and holds the gradient of a token
        ;; that is opened, so it is not only known on a paste
        gradient (mf/with-memo [reference]
                   (wtgf/css->form-value reference))
        editable? (some? gradient)

        convert-paste
        (mf/use-fn
         (fn [text]
           (when-let [converted (wtgf/converted-paste text)]
             (reset! converted* converted)
             converted)))

        on-edit
        (mf/use-fn
         (mf/deps form gradient handle-toggle)
         (fn [_]
           (swap! form (fn [state]
                         (-> state
                             (update-in [:data :value] merge (dissoc gradient :reference))
                             (update :errors dissoc :value)
                             (update :extra-errors dissoc :value))))
           (handle-toggle "composite")))]

    [:*
     [:div {:class (stl/css :input-row-reference)}
      [:div {:class (stl/css :reference-row)}
       [:> token.controls/input-composite*
        {:class (stl/css :reference-input)
         :placeholder (tr "workspace.tokens.reference-composite-gradient")
         :aria-label (tr "labels.reference")
         :icon i/tokens
         :name :reference
         :token token
         :tokens tokens
         :is-tokenscript true
         :convert-paste convert-paste}]

       ;; The icon of the tab it opens
       (when editable?
         [:> icon-button* {:variant "secondary"
                           :type "button"
                           :aria-label (tr "workspace.tokens.gradient-edit")
                           :on-click on-edit
                           :icon i/layers}])]]

     (when converted?
       [:> context-notification* {:level :warning
                                  :appearance :ghost}
        (tr "workspace.tokens.gradient-converted")])]))

(mf/defc tabs-wrapper*
  {::mf/private true}
  [{:keys [token tokens tab handle-toggle]}]
  [:*
   [:div {:class (stl/css :title-bar)}
    [:div {:class (stl/css :title)} (tr "media.gradient")]
    [:& radio-buttons {:class (stl/css :listing-options)
                       :selected (d/name tab)
                       :on-change handle-toggle
                       :name "reference-composite-tab"}
     [:& radio-button {:icon i/layers
                       :value "composite"
                       :title (tr "workspace.tokens.individual-tokens")
                       :id "composite-opt"}]
     [:& radio-button {:icon i/tokens
                       :value "reference"
                       :title (tr "workspace.tokens.use-reference")
                       :id "reference-opt"}]]]

   (if (= tab :composite)
     [:> composite-form* {:token token
                          :tokens tokens}]
     [:> reference-form* {:token token
                          :tokens tokens
                          :handle-toggle handle-toggle}])])

(defn- make-schema
  [current-token-path tokens-tree active-tab]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255
                 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/schema:token-name assoc
                             :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (ctob/token-name-path-exists? % (-> tokens-tree
                                                  (d/dissoc-in current-token-path))))]]]

     [:value
      [:map
       [:kind {:optional true} [:maybe :string]]
       [:angle {:optional true} [:maybe :string]]
       [:stops {:optional true}
        [:vector
         [:map
          [:color {:optional true} [:maybe :string]]
          [:color-result {:optional true} ::sm/any]
          [:position {:optional true} [:maybe :string]]
          [:id {:optional true} ::sm/any]]]]

       (if (= active-tab :reference)
         [:reference {:optional false} ::sm/text]
         [:reference {:optional true} [:maybe :string]])]]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field [:value :reference]
          :error/fn #(tr "errors.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (let [reference (get value :reference)]
         (if (and reference name)
           (not (cto/token-value-self-reference? name reference))
           true)))]

    [:fn {:error/fn #(tr "errors.tokens.invalid-token-value-gradient")
          :error/field :value}
     (fn [{:keys [value]}]
       (let [{:keys [reference kind stops]} value]
         (if (= active-tab :reference)
           (not (str/blank? reference))
           (and (contains? wtgf/gradient-kinds kind)
                (>= (count stops) 2)
                (every? wtgf/complete-stop? stops)))))]]))

(mf/defc form*
  [{:keys [token current-token-path] :as props}]
  (let [initial
        (mf/with-memo [token]
          {:name        (:name token "")
           :description (:description token "")
           :value       (wtgf/make-default-value (:value token))})

        props (mf/spread-props props {:initial initial
                                      :make-schema (partial make-schema current-token-path)
                                      :value-type :composite
                                      :input-component tabs-wrapper*
                                      :validator validate-gradient-token})]
    [:> generic/form* props]))
