;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.text-japanese-layout
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text.japanese-layout :as jl]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options* radio-selected]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.text.writing-mode :as wm]
   [rumext.v2 :as mf]))

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

(defn span-select-value
  [value default]
  (if (mixed-span-value? value)
    "mixed"
    (radio-selected value default)))

(defn with-mixed-span-option
  [options value]
  (cond-> options
    (mixed-span-value? value)
    (conj {:id       "mixed"
           :label    (tr "labels.mixed-values")
           :disabled true
           :dimmed   true})))

(defn japanese-layout-config-enabled?
  "True under the WASM renderer when Japanese layout is enabled for the file
  or in the user's profile. `config` is a `refs/japanese-layout-config`
  value."
  [{:keys [file? all-files?]}]
  (and ^boolean (wm/vertical-layout-active?)
       (boolean (or file? all-files?))))

(defn vertical-japanese-layout?
  "True when the Japanese layout controls are editing vertical text."
  [values]
  (= "vertical-rl" (:writing-mode values)))

(defn proportional-metrics-feature
  "Proportional metrics feature for the writing mode: `vpal` or `palt`."
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

(mf/defc attr-radio-options*
  "Radio group bound to one text attribute."
  [{:keys [values on-change on-blur attr default-value name class options]}]
  (let [selected (radio-selected (get values attr) default-value)
        handle-change
        (mf/use-fn
         (mf/deps attr on-change on-blur)
         (fn [value]
           (on-change {attr value})
           (when (some? on-blur) (on-blur))))]
    [:div {:class class}
     [:> radio-buttons* {:selected  selected
                         :on-change handle-change
                         :name      name
                         :options   options}]]))

(defn writing-mode-options
  [translate]
  [{:value "horizontal-tb"
    :id    "horizontal-tb-writing-mode"
    :label (translate "workspace.options.text-options.writing-mode-horizontal")
    :icon  i/writing-mode-horizontal}
   {:value "vertical-rl"
    :id    "vertical-rl-writing-mode"
    :label (translate "workspace.options.text-options.writing-mode-vertical")
    :icon  i/writing-mode-vertical}])

(defn text-orientation-options
  [translate]
  [{:value "mixed"
    :id    "mixed-text-orientation"
    :label (translate "workspace.options.text-options.text-orientation-mixed")
    :icon  i/text-orientation-mixed}
   {:value "upright"
    :id    "upright-text-orientation"
    :label (translate "workspace.options.text-options.text-orientation-upright")
    :icon  i/text-orientation-upright}])

;; Warichu (割注): renders the selection as two half-size lines in one inline
;; position (top/bottom horizontally, right/left vertically).
(defn warichu-options
  [translate]
  [{:value "none"
    :id    "none-warichu"
    :label (translate "workspace.options.text-options.warichu-none")
    :icon  i/warichu-none}
   {:value "warichu"
    :id    "warichu-warichu"
    :label (translate "workspace.options.text-options.warichu")
    :icon  i/warichu}])

;; select* matches options by :id, so each id is the stored value.
(defn text-combine-upright-count-options
  [translate]
  [{:id    "digits2"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-2")}
   {:id    "digits3"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-3")}
   {:id    "digits"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-4")}])

(defn ruby-size-options
  [translate]
  [{:id "half" :label (translate "workspace.options.text-options.ruby-size-half")}
   {:id "third" :label (translate "workspace.options.text-options.ruby-size-third")}
   {:id "quarter" :label (translate "workspace.options.text-options.ruby-size-quarter")}])

(defn ruby-align-options
  [translate]
  [{:id "space-around" :label (translate "workspace.options.text-options.ruby-align-space-around")}
   {:id "center" :label (translate "workspace.options.text-options.ruby-align-center")}
   {:id "start" :label (translate "workspace.options.text-options.ruby-align-start")}
   {:id "space-between" :label (translate "workspace.options.text-options.ruby-align-space-between")}])

(defn ruby-overhang-options
  [translate]
  [{:id "auto" :label (translate "workspace.options.text-options.ruby-overhang-auto")}
   {:id "none" :label (translate "workspace.options.text-options.ruby-overhang-none")}])

(defn ruby-side-options
  [translate]
  [{:id "over" :label (translate "workspace.options.text-options.ruby-side-over")}
   {:id "under" :label (translate "workspace.options.text-options.ruby-side-under")}])

(mf/defc text-combine-upright-options*
  ;; Digit TCY finds eligible runs itself, so it applies to a whole shape;
  ;; `all` is offered only for a text selection.
  [{:keys [values on-change on-blur text-selection-active]}]
  (let [text-combine-upright (radio-selected (:text-combine-upright values)
                                             (jl/enum-default :text-combine-upright))
        selected (if (jl/digit-combine? text-combine-upright) "digits" text-combine-upright)
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
  (let [text-combine-upright (radio-selected (:text-combine-upright values)
                                             (jl/enum-default :text-combine-upright))
        digits?  (jl/digit-combine? text-combine-upright)
        options  (mf/with-memo [] (text-combine-upright-count-options tr))

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

(mf/defc span-select-option*
  "Select bound to one span attribute, with a disabled entry for mixed values."
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

(mf/defc ruby-hidden-option*
  [{:keys [values on-change on-blur]}]
  (let [value (:ruby-hidden values)
        enabled? (cond
                   (mixed-span-value? value) nil
                   (true? value) true
                   :else false)
        label (tr "workspace.options.text-options.ruby-hidden")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [checked?]
           (on-change {:ruby-hidden checked?})
           (when (some? on-blur) (on-blur))))]
    [:div {:class (stl/css :ruby-hidden-option :japanese-select-option)}
     [:span {:class (stl/css :japanese-option-label)} label]
     [:> switch* {:default-checked enabled?
                  :aria-label      label
                  :on-change       handle-change}]]))

(mf/defc ruby-customization-options*
  [{:keys [values on-change on-blur]}]
  (let [common-props (ruby-common-props values on-change on-blur)
        options      (mf/with-memo []
                       {:ruby-size     (ruby-size-options tr)
                        :ruby-align    (ruby-align-options tr)
                        :ruby-overhang (ruby-overhang-options tr)
                        :ruby-side     (ruby-side-options tr)})]
    [:div {:class (stl/css :japanese-layout-controls)}
     [:> ruby-hidden-option* common-props]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-size
                                               :default-value (jl/enum-default :ruby-size)
                                               :label (tr "workspace.options.text-options.ruby-size")
                                               :options (:ruby-size options)})]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-align
                                               :default-value (jl/enum-default :ruby-align)
                                               :label (tr "workspace.options.text-options.ruby-align")
                                               :options (:ruby-align options)})]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-overhang
                                               :default-value (jl/enum-default :ruby-overhang)
                                               :label (tr "workspace.options.text-options.ruby-overhang")
                                               :options (:ruby-overhang options)})]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-side
                                               :default-value (jl/enum-default :ruby-side)
                                               :label (tr "workspace.options.text-options.ruby-side")
                                               :options (:ruby-side options)})]]))

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

(mf/defc font-features-options*
  ;; Japanese proportional metric alternates, per span: `palt` for
  ;; horizontal text, `vpal` for vertical.
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

    [:div {:class (stl/css :japanese-select-option)}
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

(defn annotation-clearance-options
  ([]
   (annotation-clearance-options tr))
  ([translate]
   [{:id "none"
     :label (translate "workspace.options.text-options.annotation-clearance-none")}
    {:id "auto"
     :label (translate "workspace.options.text-options.annotation-clearance-auto")}]))

(defn line-adjustment-options
  ([]
   (line-adjustment-options tr))
  ([translate]
   [{:id "push-in-first"
     :label (translate "workspace.options.text-options.line-adjustment-push-in-first")}
    {:id "push-out-first"
     :label (translate "workspace.options.text-options.line-adjustment-push-out-first")}
    {:id "push-out-only"
     :label (translate "workspace.options.text-options.line-adjustment-push-out-only")}]))

(mf/defc line-adjustment-option*
  "Whole-shape setting: how lines slightly too long are fitted."
  [{:keys [values on-change on-blur]}]
  (let [options (mf/with-memo [] (line-adjustment-options))]
    [:> span-select-option* {:values        values
                             :on-change     on-change
                             :on-blur       on-blur
                             :attr          :line-adjustment
                             :default-value (jl/enum-default :line-adjustment)
                             :label         (tr "workspace.options.text-options.line-adjustment")
                             :options       options}]))

(mf/defc japanese-layout-options*
  [{:keys [values ruby-values text-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [vertical?               (vertical-japanese-layout? values)
        common-props            (mf/props
                                 {:values    values
                                  :on-change on-change
                                  :on-blur   on-blur})
        ruby-presentation-props (mf/props
                                 {:values    ruby-values
                                  :on-change on-ruby-presentation-change
                                  :on-blur   on-blur})
        options                 (mf/with-memo []
                                  {:writing-mode         (writing-mode-options tr)
                                   :text-orientation     (text-orientation-options tr)
                                   :warichu              (warichu-options tr)
                                   :text-emphasis        (text-emphasis-options)
                                   :annotation-clearance (annotation-clearance-options)})]

    [:div {:class (stl/css :japanese-layout-options)}
     [:div {:class (stl/css :japanese-layout-controls)}
      [:div {:class (stl/css :japanese-icon-options)}
       [:> attr-radio-options* (mf/spread-props common-props
                                                {:attr          :writing-mode
                                                 :default-value (jl/enum-default :writing-mode)
                                                 :name          "writing-mode-options"
                                                 :class         (stl/css :writing-mode-options)
                                                 :options       (:writing-mode options)})]
       (when ^boolean vertical?
         [:*
          ;; An unset orientation reads as nil, or "" in the v2 editor; both
          ;; select "mixed".
          [:> attr-radio-options* (mf/spread-props common-props
                                                   {:attr          :text-orientation
                                                    :default-value (jl/enum-default :text-orientation)
                                                    :name          "text-orientation-options"
                                                    :class         (stl/css :text-orientation-options)
                                                    :options       (:text-orientation options)})]
          [:> text-combine-upright-options*
           (mf/spread-props common-props
                            {:text-selection-active text-selection-active})]])
       (when ^boolean text-selection-active
         [:> attr-radio-options* (mf/spread-props common-props
                                                  {:attr          :warichu
                                                   :default-value (jl/enum-default :warichu)
                                                   :name          "warichu-options"
                                                   :class         (stl/css :warichu-options)
                                                   :options       (:warichu options)})])]
      (when ^boolean vertical?
        [:> text-combine-upright-count-options* common-props])
      [:> font-features-options* common-props]
      [:> line-adjustment-option* common-props]
      ;; Emphasis marks (圏点 / bouten) apply to the selected characters.
      (when ^boolean text-selection-active
        [:> span-select-option* (mf/spread-props common-props
                                                 {:attr          :text-emphasis
                                                  :default-value (jl/enum-default :text-emphasis)
                                                  :label         (tr "workspace.options.text-options.text-emphasis")
                                                  :options       (:text-emphasis options)})])
      [:> span-select-option* (mf/spread-props common-props
                                               {:attr          :annotation-clearance
                                                :default-value (jl/enum-default :annotation-clearance)
                                                :label         (tr "workspace.options.text-options.annotation-clearance")
                                                :options       (:annotation-clearance options)})]
      (if text-selection-active
        [:> ruby-advanced-options* common-props]
        [:> ruby-presentation-options* ruby-presentation-props])]]))
