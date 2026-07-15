;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.text
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.texts-v3 :as dwt-v3]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.shared.searchable-options-dropdown :refer [searchable-options-dropdown*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options*]]
   [app.main.ui.workspace.sidebar.options.menus.token-typography-row :refer [token-typography-row*]]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [text-options* typography-entry*]]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.text.content :as content]
   [app.util.text.ui :as txu]
   [app.util.timers :as ts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private token-typography-row-enabled?
  "True when the token-typography-row feature flag is enabled.
  Evaluated once at module load time; cf/flags is immutable after startup."
  (contains? cf/flags :token-typography-row))

;; CHANGEME: I want all the new options for japanese text to be in their own namespaced and then imported here

(defn- radio-selected
  ([value]
   (radio-selected value ""))
  ([value default]
   (cond
     (= value :multiple) ""
     (or (nil? value)
         (and (string? value) (empty? value))) default
     (keyword? value) (d/name value)
     (string? value) value
     :else (str value))))

(def ^:private mixed-span-values
  #{:mixed :multiple "mixed" "multiple"})

(defn- mixed-span-value?
  [value]
  (contains? mixed-span-values value))

(defn- span-input-value
  [value]
  (if (mixed-span-value? value)
    "mixed"
    (radio-selected value)))

(defn- span-select-value
  [value default]
  (if (mixed-span-value? value)
    "mixed"
    (radio-selected value default)))

(defn- with-mixed-span-option
  [options value]
  (cond-> options
    (mixed-span-value? value)
    (conj {:id       "mixed"
           :label    (tr "labels.mixed-values")
           :disabled true
           :dimmed   true})))

(defn japanese-layout-enabled?
  "Japanese layout is opt-in. An explicit supported writing mode is the
  persisted marker; absent, mixed, or reset values remain ordinary text."
  [values]
  (let [writing-mode (:writing-mode values)]
    (cond
      (= writing-mode :multiple) nil
      (#{"horizontal-tb" "vertical-rl"} writing-mode) true
      :else false)))

(defn japanese-layout-toggle-attrs
  "Attrs emitted by the Japanese layout switch. A nil writing mode removes the
  persisted paragraph attribute, restoring the normal horizontal default."
  [enabled?]
  {:writing-mode (when enabled? "horizontal-tb")})

(defn reconcile-japanese-layout-state
  "Keep the current opt-in state when a same-selection style snapshot omits
  writing-mode. A new selection resets from its persisted paragraph value."
  [current values selection-changed?]
  (cond
    selection-changed? (japanese-layout-enabled? values)
    (#{"horizontal-tb" "vertical-rl"} (:writing-mode values)) true
    :else current))

(defn vertical-japanese-layout?
  "True when the Japanese layout controls are editing vertical text."
  [values]
  (= "vertical-rl" (:writing-mode values)))

(defn proportional-metrics-feature
  "Return the proportional metric feature relevant to the writing mode."
  [writing-mode]
  (if (= writing-mode "vertical-rl") "vpal" "palt"))

(defn text-combine-upright-options
  [text-selection-active translate]
  (cond-> [{:value "none"
            :id    "none-text-combine-upright"
            :label (translate "workspace.options.text-options.text-combine-upright-none")
            :icon  i/text-combine-upright-none}]
    text-selection-active
    (conj {:value "all"
           :id    "all-text-combine-upright"
           :label (translate "workspace.options.text-options.text-combine-upright-all")
           :icon  i/text-combine-upright-all})

    true
    (conj {:value "digits"
           :id    "digits-text-combine-upright"
           :label (translate "workspace.options.text-options.text-combine-upright-digits")
           :icon  i/text-combine-upright-digits})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sub-components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc japanese-layout-toggle*
  [{:keys [values enabled on-change on-toggle on-blur]}]
  (let [handle-change
        (mf/use-fn
         (mf/deps on-change on-toggle on-blur)
         (fn [checked?]
           (on-toggle checked?)
           (on-change (japanese-layout-toggle-attrs checked?))
           (when (some? on-blur) (on-blur))))]

    ;; Repair the invalid reset sentinel emitted by the previous implementation
    ;; so an already-open document can recover without a reload or manual edit.
    (mf/with-effect [(:writing-mode values) on-change]
      (when (= "" (:writing-mode values))
        (on-change {:writing-mode nil})))

    [:div {:class (stl/css :japanese-layout-toggle)}
     [:> switch* {:default-checked enabled
                  :label           (tr "workspace.options.text-options.japanese-layout")
                  :on-change       handle-change}]]))

(mf/defc text-align-options*
  [{:keys [values on-change on-blur]}]
  (let [options
        (mf/with-memo []
          [{:value "left"
            :id    "text-align-left"
            :label (tr "workspace.options.text-options.text-align-left")
            :icon  i/text-align-left}
           {:value "center"
            :id    "text-align-center"
            :label (tr "workspace.options.text-options.text-align-center")
            :icon  i/text-align-center}
           {:value "right"
            :id    "text-align-right"
            :label (tr "workspace.options.text-options.text-align-right")
            :icon  i/text-align-right}
           {:value "justify"
            :id    "text-align-justify"
            :label (tr "workspace.options.text-options.text-align-justify")
            :icon  i/text-justify}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-align value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :align-options)}
     [:> radio-buttons* {:selected  (radio-selected (:text-align values))
                         :on-change handle-change
                         :name      "align-text-options"
                         :options   options}]]))

(mf/defc text-direction-options*
  [{:keys [values on-change on-blur]}]
  (let [direction (radio-selected (:text-direction values))
        options
        (mf/with-memo []
          [{:value "ltr"
            :id    "ltr-text-direction"
            :label (tr "workspace.options.text-options.direction-ltr")
            :icon  i/text-ltr}
           {:value "rtl"
            :id    "rtl-text-direction"
            :label (tr "workspace.options.text-options.direction-rtl")
            :icon  i/text-rtl}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur direction)
         (fn [value]
           (on-change {:text-direction (if (= value direction) "none" value)})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :text-direction-options)}
     [:> radio-buttons* {:selected  direction
                         :on-change handle-change
                         :name      "text-direction-options"
                         :options   options}]]))

(mf/defc writing-mode-options*
  [{:keys [values on-change on-blur]}]
  (let [writing-mode (radio-selected (:writing-mode values) "horizontal-tb")
        options
        (mf/with-memo []
          [{:value "horizontal-tb"
            :id    "horizontal-tb-writing-mode"
            :label (tr "workspace.options.text-options.writing-mode-horizontal")
            :icon  i/writing-mode-horizontal}
           {:value "vertical-rl"
            :id    "vertical-rl-writing-mode"
            :label (tr "workspace.options.text-options.writing-mode-vertical")
            :icon  i/writing-mode-vertical}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:writing-mode value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :writing-mode-options)}
     [:> radio-buttons* {:selected  writing-mode
                         :on-change handle-change
                         :name      "writing-mode-options"
                         :options   options}]]))

(mf/defc text-orientation-options*
  [{:keys [values on-change on-blur]}]
  ;; The v2 editor can read the paragraph orientation back as an empty
  ;; string when it is unset; treat that (and nil) as the "mixed" default.
  (let [text-orientation (radio-selected (:text-orientation values) "mixed")
        options
        (mf/with-memo []
          [{:value "mixed"
            :id    "mixed-text-orientation"
            :label (tr "workspace.options.text-options.text-orientation-mixed")
            :icon  i/text-orientation-mixed}
           {:value "upright"
            :id    "upright-text-orientation"
            :label (tr "workspace.options.text-options.text-orientation-upright")
            :icon  i/text-orientation-upright}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-orientation value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :text-orientation-options)}
     [:> radio-buttons* {:selected  text-orientation
                         :on-change handle-change
                         :name      "text-orientation-options"
                         :options   options}]]))

(mf/defc text-combine-upright-options*
  ;; Digit TCY can be applied across a shape because it discovers eligible
  ;; runs automatically. The unrestricted `all` value is only offered for an
  ;; explicit text range.
  [{:keys [values on-change on-blur text-selection-active]}]
  (let [text-combine-upright (radio-selected (:text-combine-upright values) "none")
        digits?  (case text-combine-upright
                   ("digits" "digits2" "digits3") true
                   false)
        selected (if digits? "digits" text-combine-upright)
        options
        (mf/with-memo [text-selection-active]
          (text-combine-upright-options text-selection-active tr))

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-combine-upright value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :text-combine-upright-options)}
     [:> radio-buttons* {:selected  selected
                         :on-change handle-change
                         :name      "text-combine-upright-options"
                         :options   options}]]))

(mf/defc text-combine-upright-count-options*
  [{:keys [values on-change on-blur]}]
  (let [text-combine-upright (radio-selected (:text-combine-upright values) "none")
        digits? (case text-combine-upright
                  ("digits" "digits2" "digits3") true
                  false)
        options
        (mf/with-memo []
          ;; select* matches and reports options by :id, so the id is the
          ;; persisted attr value.
          [{:id    "digits2"
            :label (tr "workspace.options.text-options.text-combine-upright-digits-2")}
           {:id    "digits3"
            :label (tr "workspace.options.text-options.text-combine-upright-digits-3")}
           {:id    "digits"
            :label (tr "workspace.options.text-options.text-combine-upright-digits-4")}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-combine-upright value})
           (when (some? on-blur) (on-blur))))]

    (when digits?
      [:div {:class (stl/css :japanese-select-option)}
       [:span {:class (stl/css :japanese-option-label)}
        (tr "workspace.options.text-options.text-combine-upright-digits-count")]
       [:> select* {:default-selected text-combine-upright
                    :aria-label       (tr "workspace.options.text-options.text-combine-upright-digits-count")
                    :options          options
                    :on-change        handle-change}]])))

(mf/defc ruby-options*
  [{:keys [values on-change on-blur advanced-open on-toggle-advanced]}]
  (let [ruby       (span-input-value (:ruby values))
        ruby*      (mf/use-state ruby)
        dirty*     (mf/use-state false)
        value      (deref ruby*)
        dirty?     (deref dirty*)

        commit!
        (mf/use-fn
         (mf/deps dirty? value on-change on-blur)
         (fn []
           (when dirty?
             (on-change {:ruby value})
             (reset! dirty* false))
           (when (some? on-blur) (on-blur))))

        handle-change
        (mf/use-fn
         (fn [event]
           (reset! dirty* true)
           (reset! ruby* (dom/get-target-val event))))

        handle-key-down
        (mf/use-fn
         (mf/deps commit!)
         (fn [event]
           (when (= "Enter" (.-key event))
             (dom/blur! (dom/get-target event)))))]

    (mf/with-effect [ruby]
      (reset! ruby* ruby)
      (reset! dirty* false))

    [:div {:class (stl/css :ruby-options)}
     [:> input* {:class       (stl/css :ruby-input)
                 :label       (tr "workspace.options.text-options.ruby")
                 :placeholder (tr "workspace.options.text-options.ruby-placeholder")
                 :value       value
                 :on-change   handle-change
                 :on-blur     commit!
                 :on-key-down handle-key-down}]
     [:> icon-button* {:variant     "ghost"
                       :selected    advanced-open
                       :aria-label  (tr "workspace.options.text-options.ruby-advanced-options")
                       :aria-expanded advanced-open
                       :data-testid "ruby-advanced-options-toggle"
                       :on-click    on-toggle-advanced
                       :icon        i/menu}]]))

(mf/defc ruby-select-option*
  [{:keys [values on-change on-blur attr default-value label options]}]
  (let [value    (get values attr)
        selected (span-select-value value default-value)
        options  (with-mixed-span-option options value)
        handle-change
        (mf/use-fn
         (mf/deps attr on-change on-blur)
         (fn [value]
           (on-change {attr value})
           (when (some? on-blur) (on-blur))))]
    [:div {:class (stl/css :japanese-select-option)}
     [:span {:class (stl/css :japanese-option-label)} label]
     [:> select* {:default-selected selected
                  :aria-label       label
                  :options          options
                  :on-change        handle-change}]]))

(defn- ruby-common-props
  [values on-change on-blur]
  (mf/props
   {:values    values
    :on-change on-change
    :on-blur   on-blur}))

(mf/defc ruby-customization-options*
  [{:keys [values on-change on-blur]}]
  (let [common-props (ruby-common-props values on-change on-blur)
        size-options [{:id "half" :label (tr "workspace.options.text-options.ruby-size-half")}
                      {:id "third" :label (tr "workspace.options.text-options.ruby-size-third")}
                      {:id "quarter" :label (tr "workspace.options.text-options.ruby-size-quarter")}]
        align-options [{:id "space-around" :label (tr "workspace.options.text-options.ruby-align-space-around")}
                       {:id "center" :label (tr "workspace.options.text-options.ruby-align-center")}
                       {:id "start" :label (tr "workspace.options.text-options.ruby-align-start")}
                       {:id "space-between" :label (tr "workspace.options.text-options.ruby-align-space-between")}]
        overhang-options [{:id "auto" :label (tr "workspace.options.text-options.ruby-overhang-auto")}
                          {:id "none" :label (tr "workspace.options.text-options.ruby-overhang-none")}]
        side-options [{:id "over" :label (tr "workspace.options.text-options.ruby-side-over")}
                      {:id "under" :label (tr "workspace.options.text-options.ruby-side-under")}]]
    [:div {:class (stl/css :japanese-layout-controls)}
     [:> ruby-select-option* (mf/spread-props common-props
                                              {:attr :ruby-size
                                               :default-value "half"
                                               :label (tr "workspace.options.text-options.ruby-size")
                                               :options size-options})]
     [:> ruby-select-option* (mf/spread-props common-props
                                              {:attr :ruby-align
                                               :default-value "space-around"
                                               :label (tr "workspace.options.text-options.ruby-align")
                                               :options align-options})]
     [:> ruby-select-option* (mf/spread-props common-props
                                              {:attr :ruby-overhang
                                               :default-value "auto"
                                               :label (tr "workspace.options.text-options.ruby-overhang")
                                               :options overhang-options})]
     [:> ruby-select-option* (mf/spread-props common-props
                                              {:attr :ruby-side
                                               :default-value "over"
                                               :label (tr "workspace.options.text-options.ruby-side")
                                               :options side-options})]]))

(mf/defc ruby-advanced-options*
  [{:keys [values on-change on-blur]}]
  (let [open*       (mf/use-state false)
        open?       (deref open*)
        toggle-open (mf/use-fn #(swap! open* not))
        common-props (ruby-common-props values on-change on-blur)]
    [:div {:class (stl/css :ruby-advanced-options)}
     [:> ruby-options* (mf/spread-props common-props
                                        {:advanced-open     open?
                                         :on-toggle-advanced toggle-open})]
     [:> advanced-options* {:class      (stl/css :ruby-advanced-content)
                            :is-visible open?}
      [:> ruby-customization-options* common-props]]]))

(mf/defc ruby-presentation-options*
  [{:keys [values on-change on-blur]}]
  (let [open*        (mf/use-state false)
        open?        (deref open*)
        toggle-open  (mf/use-fn #(swap! open* not))
        common-props (ruby-common-props values on-change on-blur)]
    [:div {:class (stl/css :ruby-advanced-options)}
     [:> title-bar* {:collapsable     true
                     :collapsed       (not open?)
                     :title           (tr "workspace.options.text-options.ruby-advanced-options")
                     :on-collapsed    toggle-open
                     :aria-expanded   open?
                     :data-testid     "ruby-presentation-options-toggle"}]
     [:> advanced-options* {:class      (stl/css :ruby-advanced-content)
                            :is-visible open?}
      [:> ruby-customization-options* common-props]]]))

(mf/defc warichu-options*
  ;; Warichu (割注): a span-scoped toggle that renders the selection as two
  ;; half-size lines within one inline position (top/bottom in horizontal
  ;; flow, right/left in vertical flow). Applies to the current selection
  ;; through the shared node-attr on-change; "none" is the default.
  [{:keys [values on-change on-blur]}]
  (let [warichu (radio-selected (:warichu values) "none")
        options
        (mf/with-memo []
          [{:value "none"
            :id    "none-warichu"
            :label (tr "workspace.options.text-options.warichu-none")
            :icon  i/warichu-none}
           {:value "warichu"
            :id    "warichu-warichu"
            :label (tr "workspace.options.text-options.warichu")
            :icon  i/warichu}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:warichu value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :warichu-options)}
     [:> radio-buttons* {:selected  warichu
                         :on-change handle-change
                         :name      "warichu-options"
                         :options   options}]]))

(mf/defc font-features-options*
  ;; Japanese proportional metric alternates. `palt` is typically used for
  ;; horizontal composition and `vpal` for vertical composition; the value is
  ;; span-scoped and passed through browser, export, and render-wasm paths.
  [{:keys [values on-change on-blur]}]
  (let [writing-mode (:writing-mode values)
        feature (proportional-metrics-feature writing-mode)
        value (:font-features values)
        enabled? (cond
                   (mixed-span-value? value) nil
                   (= feature value) true
                   :else false)

        handle-change
        (mf/use-fn
         (mf/deps feature on-change on-blur)
         (fn [checked?]
           (on-change {:font-features (if checked? feature "none")})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :font-features-options :japanese-select-option)}
     [:span {:class (stl/css :japanese-option-label)}
      (tr "workspace.options.text-options.font-features")]
     [:> switch* {:default-checked enabled?
                  :aria-label      (tr "workspace.options.text-options.font-features")
                  :on-change       handle-change}]]))

(defn text-emphasis-options
  ([]
   (text-emphasis-options tr))
  ([translate]
   [{:id "none"
     :label (translate "workspace.options.text-options.text-emphasis-none")}
    {:id "filled-dot"
     :label (translate "workspace.options.text-options.text-emphasis-filled-dot")}
    {:id "open-dot"
     :label (translate "workspace.options.text-options.text-emphasis-open-dot")}
    {:id "filled-circle"
     :label (translate "workspace.options.text-options.text-emphasis-filled-circle")}
    {:id "open-circle"
     :label (translate "workspace.options.text-options.text-emphasis-open-circle")}
    {:id "filled-sesame"
     :label (translate "workspace.options.text-options.text-emphasis-filled-sesame")}
    {:id "open-sesame"
     :label (translate "workspace.options.text-options.text-emphasis-open-sesame")}]))

(mf/defc text-emphasis-options*
  ;; Emphasis marks (圏点 / bouten): a span-scoped style drawn beside each base
  ;; character in vertical writing. Applies to the current selection through the
  ;; shared node-attr on-change; "none" is the default.
  [{:keys [values on-change on-blur]}]
  (let [value (:text-emphasis values)
        text-emphasis (span-select-value value "none")
        options
        (mf/with-memo []
          (text-emphasis-options))
        options (with-mixed-span-option options value)

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-emphasis value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :text-emphasis-options :japanese-select-option)}
     [:span {:class (stl/css :japanese-option-label)}
      (tr "workspace.options.text-options.text-emphasis")]
     [:> select* {:default-selected text-emphasis
                  :aria-label (tr "workspace.options.text-options.text-emphasis")
                  :options options
                  :on-change handle-change}]]))

(defn annotation-clearance-options
  ([]
   (annotation-clearance-options tr))
  ([translate]
   [{:id "none"
     :label (translate "workspace.options.text-options.annotation-clearance-none")}
    {:id "auto"
     :label (translate "workspace.options.text-options.annotation-clearance-auto")}]))

(mf/defc annotation-clearance-options*
  [{:keys [values on-change on-blur]}]
  (let [value (:annotation-clearance values)
        annotation-clearance (span-select-value value "none")
        options (mf/with-memo [] (annotation-clearance-options))
        options (with-mixed-span-option options value)
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:annotation-clearance value})
           (when (some? on-blur) (on-blur))))]
    [:div {:class (stl/css :annotation-clearance-options :japanese-select-option)}
     [:span {:class (stl/css :japanese-option-label)}
      (tr "workspace.options.text-options.annotation-clearance")]
     [:> select* {:default-selected annotation-clearance
                  :aria-label (tr "workspace.options.text-options.annotation-clearance")
                  :options options
                  :on-change handle-change}]]))

(mf/defc vertical-align*
  [{:keys [values on-change on-blur]}]
  (let [vertical-align (radio-selected (:vertical-align values) "top")
        options
        (mf/with-memo []
          [{:value "top"
            :id    "vertical-text-align-top"
            :label (tr "workspace.options.text-options.align-top")
            :icon  i/text-top}
           {:value "center"
            :id    "vertical-text-align-center"
            :label (tr "workspace.options.text-options.align-middle")
            :icon  i/text-middle}
           {:value "bottom"
            :id    "vertical-text-align-bottom"
            :label (tr "workspace.options.text-options.align-bottom")
            :icon  i/text-bottom}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:vertical-align value})
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :vertical-align-options)}
     [:> radio-buttons* {:selected  vertical-align
                         :on-change handle-change
                         :name      "vertical-align-text-options"
                         :options   options}]]))

(mf/defc grow-options*
  [{:keys [ids values on-blur]}]
  (let [grow-type       (:grow-type values)
        selected        (radio-selected grow-type)
        editor-instance (mf/deref refs/workspace-editor)
        options
        (mf/with-memo []
          [{:value "fixed"
            :id    "text-fixed-grow"
            :label (tr "workspace.options.text-options.grow-fixed")
            :icon  i/text-fixed}
           {:value "auto-width"
            :id    "text-auto-width-grow"
            :label (tr "workspace.options.text-options.grow-auto-width")
            :icon  i/text-auto-width}
           {:value "auto-height"
            :id    "text-auto-height-grow"
            :label (tr "workspace.options.text-options.grow-auto-height")
            :icon  i/text-auto-height}])

        handle-change
        (mf/use-fn
         (mf/deps ids on-blur editor-instance)
         (fn [value]
           (on-blur)
           (let [uid (js/Symbol)
                 grow-type (keyword value)]
             (st/emit! (dwu/start-undo-transaction uid))
             (when (features/active-feature? @st/state "text-editor/v2")
               (let [content (when editor-instance
                               (content/dom->cljs (dwt/get-editor-root editor-instance)))]
                 (when (some? content)
                   (st/emit! (dwt/v2-update-text-shape-content (first ids) content :finalize? true)))))

             (st/emit! (dwsh/update-shapes ids #(assoc % :grow-type grow-type)))

             (when (features/active-feature? @st/state "render-wasm/v1")
               (st/emit! (dwwt/resize-wasm-text-all ids)
                         (ptk/data-event :layout/update {:ids ids})))
             ;; We asynchronously commit so every sychronous event is resolved first and inside the transaction
             (ts/schedule #(st/emit! (dwu/commit-undo-transaction uid))))
           (when (some? on-blur) (on-blur))))]

    [:div {:class (stl/css :grow-options)}
     [:> radio-buttons* {:selected  selected
                         :on-change handle-change
                         :name      "grow-text-options"
                         :options   options}]]))

(mf/defc text-decoration-options*
  [{:keys [values on-change on-blur token-applied]}]
  (let [text-decoration (radio-selected (:text-decoration values))
        options
        (mf/with-memo [token-applied]
          [{:value    "underline"
            :id       "underline-text-decoration"
            :disabled (and token-typography-row-enabled? (some? token-applied))
            :label    (tr "workspace.options.text-options.underline" (sc/get-tooltip :underline))
            :icon     i/text-underlined}
           {:value    "line-through"
            :id       "line-through-text-decoration"
            :disabled (and token-typography-row-enabled? (some? token-applied))
            :label    (tr "workspace.options.text-options.strikethrough" (sc/get-tooltip :line-through))
            :icon     i/text-stroked}])

        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [value]
           (on-change {:text-decoration value})
           (when (some? on-blur)
             (on-blur))))]

    [:div {:class (stl/css :text-decoration-options)}
     [:> radio-buttons* {:selected     (if (= text-decoration "none")
                                         ""
                                         (radio-selected text-decoration))
                         :on-change    handle-change
                         :name         "text-decoration-options"
                         :disabled     (and token-typography-row-enabled? (some? token-applied))
                         :allow-empty  true
                         :options      options}]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-option-by-name [options name]
  (let [options (if (delay? options) (deref options) options)]
    (d/seek #(= name (get % :name)) options)))

(defn- resolve-delay [tokens]
  (if (delay? tokens) @tokens tokens))

(defn- find-token-by-id [tokens id]
  (->> (:typography tokens)
       (d/seek #(= (:id %) (uuid/uuid id)))))

(defn- check-props [n-props o-props]
  (let [o-values      (unchecked-get o-props "values")
        n-values      (unchecked-get n-props "values")
        o-ruby-values (unchecked-get o-props "rubyValues")
        n-ruby-values (unchecked-get n-props "rubyValues")]
    (and (identical? (unchecked-get n-props "ids")
                     (unchecked-get o-props "ids"))
         (identical? (unchecked-get n-props "type")
                     (unchecked-get o-props "type"))
         (identical? (unchecked-get n-props "appliedTokens")
                     (unchecked-get o-props "appliedTokens"))
         (identical? (unchecked-get n-props "fileId")
                     (unchecked-get o-props "fileId"))
         (identical? (unchecked-get n-props "typographies")
                     (unchecked-get o-props "typographies"))
         (identical? (unchecked-get n-props "textSelectionActive")
                     (unchecked-get o-props "textSelectionActive"))
         (= n-ruby-values o-ruby-values)
         (identical? (get o-values :fills)
                     (get n-values :fills))
         (identical? (get o-values :font-family)
                     (get n-values :font-family))
         (identical? (get o-values :font-id)
                     (get n-values :font-id))
         (identical? (get o-values :font-size)
                     (get n-values :font-size))
         (identical? (get o-values :font-style)
                     (get n-values :font-style))
         (identical? (get o-values :font-variant-id)
                     (get n-values :font-variant-id))
         (identical? (get o-values :font-weight)
                     (get n-values :font-weight))
         (identical? (get o-values :grow-type)
                     (get n-values :grow-type))
         (identical? (get o-values :letter-spacing)
                     (get n-values :letter-spacing))
         (identical? (get o-values :line-height)
                     (get n-values :line-height))
         (identical? (get o-values :text-align)
                     (get n-values :text-align))
         (identical? (get o-values :text-decoration)
                     (get n-values :text-decoration))
         (identical? (get o-values :text-direction)
                     (get n-values :text-direction))
         (identical? (get o-values :writing-mode)
                     (get n-values :writing-mode))
         (identical? (get o-values :text-orientation)
                     (get n-values :text-orientation))
         (identical? (get o-values :text-combine-upright)
                     (get n-values :text-combine-upright))
         (identical? (get o-values :warichu)
                     (get n-values :warichu))
         (identical? (get o-values :font-features)
                     (get n-values :font-features))
         (identical? (get o-values :annotation-clearance)
                     (get n-values :annotation-clearance))
         (identical? (get o-values :text-emphasis)
                     (get n-values :text-emphasis))
         (identical? (get o-values :ruby)
                     (get n-values :ruby))
         (identical? (get o-values :ruby-size)
                     (get n-values :ruby-size))
         (identical? (get o-values :ruby-align)
                     (get n-values :ruby-align))
         (identical? (get o-values :ruby-overhang)
                     (get n-values :ruby-overhang))
         (identical? (get o-values :ruby-side)
                     (get n-values :ruby-side))
         (identical? (get o-values :text-transform)
                     (get n-values :text-transform))
         (identical? (get o-values :typography-ref-file)
                     (get n-values :typography-ref-file))
         (identical? (get o-values :typography-ref-id)
                     (get n-values :typography-ref-id))
         (identical? (get o-values :vertical-align)
                     (get n-values :vertical-align)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc text-menu*
  {::mf/wrap [#(mf/memo' % check-props)]}
  [{:keys [ids type values ruby-values text-selection-active
           applied-tokens libraries file-id typographies]}]

  (let [;; --- UI state
        menu-state*          (mf/use-state {:main-menu true
                                            :more-options false})
        menu-state           (deref menu-state*)
        main-menu-open?      (:main-menu menu-state)
        more-options-open?   (:more-options menu-state)

        token-dropdown-open* (mf/use-state false)
        token-dropdown-open? (deref token-dropdown-open*)

        ;; --- Applied token
        applied-token-name   (:typography applied-tokens)
        current-token-name*  (mf/use-state applied-token-name)
        current-token-name   (deref current-token-name*)

        ;; --- Available tokens
        active-tokens        (mf/use-ctx ctx/active-tokens-by-type)
        typography-tokens    (mf/with-memo [active-tokens] (csu/filter-tokens-for-input active-tokens :typography))

        ;; --- Dropdown
        listbox-id           (mf/use-id)
        nodes-ref            (mf/use-ref nil)
        dropdown-ref         (mf/use-ref nil)

        dropdown-options
        (mf/with-memo [typography-tokens]
          (csu/get-token-dropdown-options typography-tokens nil))

        selected-token-id*
        (mf/use-state #(when (and (not= :multiple current-token-name) current-token-name)
                         (:id (get-option-by-name dropdown-options current-token-name))))
        selected-token-id (deref selected-token-id*)

        ;; --- Typography
        typography-id      (:typography-ref-id values)
        typography-file-id (:typography-ref-file values)

        typography
        (mf/with-memo [typography-id typography-file-id file-id libraries]
          (cond
            (and typography-id
                 (not= typography-id :multiple)
                 (not= typography-file-id file-id))
            (-> (get-in libraries [typography-file-id :data :typographies typography-id])
                (assoc :file-id typography-file-id))

            (and typography-id
                 (not= typography-id :multiple)
                 (= typography-file-id file-id))
            (get typographies typography-id)))

        ;; --- Helpers
        multiple?       (->> values vals (d/seek #(= % :multiple)))

        vertical-text-enabled?
        (features/use-feature "text-vertical/v1")

        selection-key               (hash ids)
        previous-selection-key-ref (mf/use-ref selection-key)
        japanese-layout-active* (mf/use-state #(japanese-layout-enabled? values))
        japanese-layout-active?
        (deref japanese-layout-active*)
        vertical-mode?
        (vertical-japanese-layout? values)

        apply-token!
        (mf/use-fn
         (mf/deps ids typography-tokens)
         (fn [id]
           (let [token (find-token-by-id (resolve-delay typography-tokens) id)]
             (reset! selected-token-id* id)
             (reset! token-dropdown-open* false)
             (st/emit!
              (dwta/apply-token {:shape-ids         ids
                                 :attributes        #{:typography}
                                 :token             token
                                 :on-update-shape   dwta/update-typography})))))
        label
        (mf/with-memo [type]
          (case type
            :multiple (tr "workspace.options.text-options.title-selection")
            :group (tr "workspace.options.text-options.title-group")
            (tr "workspace.options.text-options.title")))
        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (d/nilv (mf/ref-val nodes-ref) #js {})
                 id    (dom/get-data node "id")]
             (mf/set-ref-val! nodes-ref (obj/set! state id node))
             (fn []
               (let [state (d/nilv (mf/ref-val nodes-ref) #js {})]
                 (mf/set-ref-val! nodes-ref (obj/unset! state id)))))))

        ;; --- Toggles
        toggle-main-menu
        (mf/use-fn
         #(swap! menu-state* update :main-menu not))

        toggle-more-options
        (mf/use-fn
         #(swap! menu-state* update :more-options not))

        toggle-token-dropdown
        (mf/use-fn
         #(swap! token-dropdown-open* not))

        toggle-japanese-layout
        (mf/use-fn
         #(reset! japanese-layout-active* %))

        ;; --- Event handlers
        on-option-click
        (mf/use-fn
         (mf/deps apply-token!)
         (fn [event]
           (dom/stop-propagation event)
           (let [id (dom/get-data (dom/get-current-target event) "id")]
             (apply-token! id))))

        emit-update!
        (mf/use-fn
         (mf/deps values)
         (fn [ids attrs]
           (let [updated-attrs (-> (merge (txt/get-default-text-attrs) values attrs)
                                   (select-keys txt/text-node-attrs))]
             (when (features/active-feature? @st/state "text-editor-wasm/v1")
               (st/emit! (dwt-v3/v3-update-text-editor-styles (first ids) attrs)))
             (st/emit! (dwt/save-font updated-attrs)
                       (dwt/update-all-attrs ids attrs)))))

        on-change
        (mf/use-fn
         (mf/deps ids emit-update!)
         (fn [attrs]
           (emit-update! ids attrs)))

        on-ruby-presentation-change
        (mf/use-fn
         (mf/deps ids on-change text-selection-active)
         (fn [attrs]
           (if text-selection-active
             (on-change attrs)
             (st/emit! (dwt/update-all-ruby-presentation ids attrs)))))

        on-convert-to-typography
        (mf/use-fn
         (mf/deps values ids file-id emit-update!)
         (fn [_]
           (let [set-values (-> (d/without-nils values)
                                (select-keys (d/concat-vec txt/text-font-attrs
                                                           txt/text-spacing-attrs
                                                           txt/text-transform-attrs)))
                 typography (-> (merge txt/default-typography set-values)
                                (dwt/generate-typography-name))
                 id         (uuid/next)]
             (st/emit! (dwl/add-typography (assoc typography :id id) false))
             (emit-update! ids {:typography-ref-id id :typography-ref-file file-id}))))

        handle-detach-typography
        (mf/use-fn
         (mf/deps on-change)
         #(on-change {:typography-ref-file nil :typography-ref-id nil}))

        handle-change-typography
        (mf/use-fn
         (mf/deps typography file-id)
         (fn [changes]
           (st/emit! (dwl/update-typography (merge typography changes) file-id))))

        detach-token
        (mf/use-fn
         (mf/deps ids)
         (fn [token-name]
           (st/emit! (dwta/unapply-token {:token-name  token-name
                                          :attributes  #{:typography}
                                          :shape-ids   ids}))))

        handle-detach-all-tokens
        (mf/use-fn
         (mf/deps ids)
         (fn []
           (st/emit! (dwta/unapply-multiple-tokens {:attributes  #{:typography}
                                                    :shape-ids   ids}))))

        expand-stream
        (mf/with-memo []
          (->> st/stream (rx/filter (ptk/type? :expand-text-more-options))))

        on-text-blur
        (mf/use-fn
         (fn []
           (ts/schedule
            100
            (fn []
              (when (not= "INPUT" (-> (dom/get-active) dom/get-tag-name))
                (dom/focus! (txu/get-text-editor-content)))))))

        common-props (mf/props
                      {:ids         ids
                       :values      values
                       :on-change   on-change
                       :show-recent true
                       :on-blur     on-text-blur})

        ruby-presentation-props
        (mf/props
         {:values    ruby-values
          :on-change on-ruby-presentation-change
          :on-blur   on-text-blur})

        japanese-toggle-props
        (mf/spread-props common-props
                         {:enabled   japanese-layout-active?
                          :on-toggle toggle-japanese-layout})]

    (hooks/use-stream
     expand-stream
     #(swap! menu-state* assoc :more-options true))

    (mf/with-effect [applied-token-name]
      (reset! current-token-name* applied-token-name))

    (mf/with-effect [applied-token-name dropdown-options]
      (reset! selected-token-id*
              (when (and (not= :multiple applied-token-name) applied-token-name)
                (:id (get-option-by-name dropdown-options applied-token-name)))))

    (mf/with-effect [token-dropdown-open?]
      (when token-dropdown-open?
        (ts/schedule 0 #(some-> (mf/ref-val dropdown-ref) dom/focus!))))

    ;; Selection style snapshots may temporarily omit paragraph attrs when a
    ;; span-level Japanese option changes. A new selection must instead trust
    ;; its persisted value. Handle both changes in one effect so a writing-mode
    ;; update cannot restore the previous selection's opt-in state.
    (mf/with-effect [selection-key (:writing-mode values)]
      (let [selection-changed?
            (not= selection-key (mf/ref-val previous-selection-key-ref))]
        (reset! japanese-layout-active*
                (reconcile-japanese-layout-state japanese-layout-active?
                                                 values
                                                 selection-changed?))
        (mf/set-ref-val! previous-selection-key-ref selection-key)))

    [:section {:class      (stl/css :element-set)
               :aria-label (tr "workspace.options.text-options.text-section")}
     [:div {:class (stl/css :element-title)}
      [:> title-bar* {:collapsable  true
                      :collapsed    (not main-menu-open?)
                      :on-collapsed toggle-main-menu
                      :title        label
                      :class        (stl/css :title-spacing-text)}
       [:*
        (when (and token-typography-row-enabled? (some? (resolve-delay typography-tokens)) (not typography))
          [:> icon-button* {:variant           "ghost"
                            :aria-label        (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                            :on-click          toggle-token-dropdown
                            :tooltip-placement "top-left"
                            :icon              i/tokens}])
        (when (and (not typography) (not multiple?) (not applied-token-name))
          [:> icon-button* {:variant           "ghost"
                            :aria-label        (tr "workspace.options.convert-to-typography")
                            :on-click          on-convert-to-typography
                            :tooltip-placement "top-left"
                            :icon              i/add}])]]
      (when (and token-typography-row-enabled? token-dropdown-open?)
        [:> searchable-options-dropdown* {:on-click     on-option-click
                                          :id           listbox-id
                                          :options      (resolve-delay dropdown-options)
                                          :selected     selected-token-id
                                          :align        "right"
                                          :placeholder  (tr "workspace.tokens.search-by-token")
                                          :ref          set-option-ref}])]

     (when main-menu-open?
       [:div {:class (stl/css :element-content)}
        (cond
          (and token-typography-row-enabled? (= :multiple current-token-name) (= typography-id :multiple))
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)}
            (tr "workspace.libraries.text.mixed-tokens-and-assets")]]

          (and token-typography-row-enabled? (= :multiple current-token-name))
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)}
            (tr "workspace.libraries.text.mixed-tokens")]
           [:> icon-button* {:variant    "ghost"
                             :aria-label (tr "workspace.libraries.text.multiple-token-tooltip")
                             :tooltip-placement "top-left"
                             :on-click   handle-detach-all-tokens
                             :icon       i/detach}]]

          (and token-typography-row-enabled? current-token-name)
          [:> token-typography-row* {:token-name    current-token-name
                                     :detach-token  detach-token
                                     :active-tokens (resolve-delay typography-tokens)}]

          (= typography-id :multiple)
          [:div {:class (stl/css :multiple-typography)}
           [:span {:class (stl/css :multiple-text)}
            (tr "workspace.libraries.text.mixed-typography")]
           [:> icon-button* {:variant    "ghost"
                             :aria-label (tr "workspace.libraries.text.multiple-assets-tooltip")
                             :on-click   handle-detach-typography
                             :tooltip-placement "top-left"
                             :icon       i/detach}]]

          typography
          [:> typography-entry* {:file-id    typography-file-id
                                 :typography typography
                                 :is-local   (= typography-file-id file-id)
                                 :on-detach  handle-detach-typography
                                 :on-change  handle-change-typography}]



          :else
          [:> text-options* common-props])

        [:div {:class (stl/css :text-align-options)}
         [:> text-align-options* common-props]
         [:> grow-options* common-props]
         [:> icon-button* {:variant     "ghost"
                           :aria-label  (tr "labels.options")
                           :data-testid "text-align-options-button"
                           :on-click    toggle-more-options
                           :icon        i/menu}]]

        (when more-options-open?
          [:*
           [:div {:class (stl/css :text-decoration-options)}
            [:> vertical-align* common-props]
            [:> text-decoration-options* (mf/spread-props common-props {:token-applied current-token-name})]
            [:> text-direction-options* common-props]]
           (when ^boolean vertical-text-enabled?
             [:div {:class (stl/css :japanese-layout-options)}
              [:> japanese-layout-toggle* japanese-toggle-props]
              (when ^boolean japanese-layout-active?
                [:div {:class (stl/css :japanese-layout-controls)}
                 [:div {:class (stl/css :japanese-icon-options)}
                  [:> writing-mode-options* common-props]
                  (when ^boolean vertical-mode?
                    [:*
                     [:> text-orientation-options* common-props]
                     [:> text-combine-upright-options*
                      (mf/spread-props common-props
                                       {:text-selection-active text-selection-active})]])
                  (when ^boolean text-selection-active
                    [:> warichu-options* common-props])]
                 (when ^boolean vertical-mode?
                   [:> text-combine-upright-count-options* common-props])
                 [:> font-features-options* common-props]
                 (when ^boolean text-selection-active
                   [:> text-emphasis-options* common-props])
                 [:> annotation-clearance-options* common-props]
                 (if text-selection-active
                   [:> ruby-advanced-options* common-props]
                   [:> ruby-presentation-options* ruby-presentation-props])])])])])]))
