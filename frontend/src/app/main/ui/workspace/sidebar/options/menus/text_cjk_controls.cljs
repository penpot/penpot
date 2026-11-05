;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.text-cjk-controls
  "Controls the CJK language panels share: writing mode, readings (ruby),
  emphasis marks and annotation positions. The panels of each language
  are in `text-language-layout`."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text.cjk-layout :as cjk]
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
   [rumext.v2 :as mf]))

(def ^:private mixed-span-values
  #{:mixed :multiple "mixed" "multiple"})

(defn mixed-span-value?
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
           :label    (tr "settings.multiple")
           :disabled true
           :dimmed   true})))

(defn vertical-writing?
  "True when the option `values` are of vertical text. The panels only
  show under a CJK language, so the writing mode alone decides."
  [values]
  (= "vertical-rl" (:writing-mode values)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generic controls
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc option-row*
  "Row of a language panel: a `label` and the control in `children`."
  [{:keys [label children]}]
  [:div {:class (stl/css :option-row)}
   [:span {:class (stl/css :option-label)} label]
   children])

(mf/defc attr-radio-options*
  "Radio group bound to one text attribute."
  [{:keys [values on-change on-blur attr default-value name options]}]
  (let [selected (radio-selected (get values attr) default-value)
        group-id (mf/use-id)
        options (mf/with-memo [options group-id]
                  (mapv #(update % :id (fn [id] (str group-id "-" id))) options))
        name (str group-id "-" name)
        handle-change
        (mf/use-fn
         (mf/deps attr on-change on-blur)
         (fn [value]
           (on-change {attr value})
           (when (some? on-blur) (on-blur))))]
    [:div {:class (stl/css :radio-options)}
     [:> radio-buttons* {:selected  selected
                         :on-change handle-change
                         :name      name
                         :options   options}]]))

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
    [:> option-row* {:label label}
     [:> select* {:default-selected selected
                  :aria-label       label
                  :options          options
                  :on-change        handle-change}]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Writing mode
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(mf/defc writing-mode-controls*
  "Writing mode row, with the orientation for vertical text. `children` add
  the radio groups of a language to the row."
  [{:keys [values on-change on-blur children]}]
  (let [props   (mf/props {:values values :on-change on-change :on-blur on-blur})
        options (mf/with-memo []
                  {:writing-mode     (writing-mode-options tr)
                   :text-orientation (text-orientation-options tr)})]
    [:div {:class (stl/css :icon-options)}
     [:> attr-radio-options* (mf/spread-props props
                                              {:attr :writing-mode
                                               :default-value (cjk/enum-default :writing-mode)
                                               :name "writing-mode-options"
                                               :options (:writing-mode options)})]
     ;; An unset orientation reads as nil, or "" in the v2 editor; both
     ;; select "mixed".
     (when (vertical-writing? values)
       [:> attr-radio-options* (mf/spread-props props
                                                {:attr :text-orientation
                                                 :default-value (cjk/enum-default :text-orientation)
                                                 :name "text-orientation-options"
                                                 :options (:text-orientation options)})])
     children]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Positions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Annotation positions store both axes (over or under the line, right or
;; left of the column), but only one applies to a writing mode: above/below
;; in horizontal text, right/left in vertical text. The controls show the
;; one that applies and keep the stored other.

(defn- position-sides
  "Block (`\"over\"`/`\"under\"`) and inline (`\"right\"`/`\"left\"`) side
  of a position `value`, nil for the ones it leaves to the renderer."
  [attr value]
  (case attr
    :ruby-side
    [value nil]

    :text-emphasis-position
    (when (and (string? value) (not= "auto" value))
      (let [[block inline] (.split value "-")] [block inline]))

    :text-underline-position
    (case value
      "under"       ["under" nil]
      "left"        [nil "left"]
      "right"       [nil "right"]
      "under-left"  ["under" "left"]
      "under-right" ["under" "right"]
      nil)))

(defn- position-of-sides
  [attr [block inline]]
  (case attr
    :ruby-side               block
    :text-emphasis-position  (if (some? block) (str block "-" inline) "auto")
    :text-underline-position (cond
                               (and block inline) (str block "-" inline)
                               (or block inline)  (or block inline)
                               :else              "auto")))

(defn position-options
  "Choices of the position `attr` for the writing mode."
  [attr vertical]
  (let [auto   {:id "auto" :label (tr "workspace.options.text-options.position-auto")}
        over   {:id "over" :label (tr "workspace.options.text-options.position-over")}
        under  {:id "under" :label (tr "workspace.options.text-options.position-under")}
        right  {:id "right" :label (tr "workspace.options.text-options.position-right")}
        left   {:id "left" :label (tr "workspace.options.text-options.position-left")}
        ;; Ruby takes the block side in vertical text too: over reads right.
        ruby-v [(assoc right :id "over") (assoc left :id "under")]]
    (case attr
      :ruby-side               (if ^boolean vertical ruby-v [over under])
      :text-emphasis-position  (if ^boolean vertical [auto right left] [auto over under])
      :text-underline-position (if ^boolean vertical [auto right left] [auto under]))))

(defn position-choice
  "Choice of `position-options` a stored position `value` shows."
  [attr vertical value]
  (if (mixed-span-value? value)
    value
    (let [[block inline] (position-sides attr value)]
      (cond
        (= :ruby-side attr)  (or block (cjk/enum-default :ruby-side))
        ^boolean vertical    (or inline "auto")
        :else                (or block "auto")))))

(defn position-value
  "Stored position for picking `choice` over the stored `value`: the side of
  the other writing mode stays as it was."
  [attr vertical value choice]
  (cond
    (= :ruby-side attr)
    choice

    ;; Emphasis is auto as a whole, or sets both sides.
    (= :text-emphasis-position attr)
    (if (= "auto" choice)
      "auto"
      (let [[block inline] (when-not (mixed-span-value? value) (position-sides attr value))]
        (position-of-sides attr (if ^boolean vertical
                                  [(or block "over") choice]
                                  [choice (or inline "right")]))))

    :else
    (let [[block inline] (when-not (mixed-span-value? value) (position-sides attr value))
          choice         (when (not= "auto" choice) choice)]
      (position-of-sides attr (if ^boolean vertical [block choice] [choice inline])))))

(mf/defc position-select-option*
  "Select of the position `attr` with the sides of the writing mode only."
  [{:keys [values on-change on-blur attr vertical label]}]
  (let [value    (get values attr)
        selected (span-select-value (position-choice attr vertical value) "auto")
        options  (mf/with-memo [attr vertical value]
                   (with-mixed-span-option (position-options attr vertical) value))
        handle-change
        (mf/use-fn
         (mf/deps attr vertical value on-change on-blur)
         (fn [choice]
           (on-change {attr (position-value attr vertical value choice)})
           (when (some? on-blur) (on-blur))))]
    [:> option-row* {:label label}
     [:> select* {:default-selected selected
                  :aria-label       label
                  :options          options
                  :on-change        handle-change}]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Emphasis marks and annotation clearance
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(mf/defc text-emphasis-option*
  "Emphasis mark (圏点, 着重号) of the spans."
  [{:keys [values on-change on-blur]}]
  (let [options (mf/with-memo [] (text-emphasis-options))]
    [:> span-select-option* {:values        values
                             :on-change     on-change
                             :on-blur       on-blur
                             :attr          :text-emphasis
                             :default-value (cjk/enum-default :text-emphasis)
                             :label         (tr "workspace.options.text-options.text-emphasis")
                             :options       options}]))

(mf/defc annotation-clearance-option*
  "Whether readings and emphasis marks add to the line height."
  [{:keys [values on-change on-blur]}]
  (let [options (mf/with-memo [] (annotation-clearance-options))]
    [:> span-select-option* {:values        values
                             :on-change     on-change
                             :on-blur       on-blur
                             :attr          :annotation-clearance
                             :default-value (cjk/enum-default :annotation-clearance)
                             :label         (tr "workspace.options.text-options.annotation-clearance")
                             :options       options}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Readings (ruby)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; A reading is furigana in Japanese and pinyin or zhuyin in Chinese. The
;; panel of each language passes its `labels`.

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

(mf/defc ruby-options*
  "Input of the reading of the selected span."
  [{:keys [values on-change on-blur advanced-open on-toggle-advanced labels]}]
  (let [ruby       (span-input-value (:ruby values))
        ruby*      (mf/use-state ruby)
        dirty*     (mf/use-state false)
        value      (deref ruby*)
        dirty?     (deref dirty*)

        valid? (or (empty? value) (nil? (:validate labels)) ((:validate labels) value))

        commit!
        (mf/use-fn
         (mf/deps dirty? valid? value on-change on-blur)
         (fn []
           (when (and dirty? valid?)
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
                 :label       (:input labels)
                 :placeholder (:placeholder labels)
                 :value       value
                 :on-change   handle-change
                 :on-blur     commit!
                 :on-key-down handle-key-down}]
     (when-not valid?
       [:p {:role "alert"} (:invalid labels)])
     (when on-toggle-advanced
       [:> icon-button* {:variant     "ghost"
                         :selected    advanced-open
                         :aria-label  (:advanced labels)
                         :aria-expanded advanced-open
                         :data-testid "ruby-advanced-options-toggle"
                         :on-click    on-toggle-advanced
                         :icon        i/menu}])]))

(mf/defc ruby-hidden-option*
  "Switch hiding the readings, unset when the spans differ."
  [{:keys [values on-change on-blur]}]
  (let [value    (:ruby-hidden values)
        enabled? (cond
                   (mixed-span-value? value) nil
                   (true? value) true
                   :else false)
        label    (tr "workspace.options.text-options.ruby-hidden")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [checked?]
           (on-change {:ruby-hidden checked?})
           (when (some? on-blur) (on-blur))))]
    [:> option-row* {:label label}
     [:> switch* {:default-checked enabled?
                  :aria-label      label
                  :on-change       handle-change}]]))

(defn- ruby-common-props
  [values on-change on-blur labels vertical]
  (mf/props
   {:values    values
    :on-change on-change
    :on-blur   on-blur
    :labels    labels
    :vertical  vertical}))

(mf/defc ruby-customization-options*
  [{:keys [values on-change on-blur labels vertical]}]
  (let [common-props (ruby-common-props values on-change on-blur labels vertical)
        options      (mf/with-memo []
                       {:ruby-size     (ruby-size-options tr)
                        :ruby-align    (ruby-align-options tr)
                        :ruby-overhang (ruby-overhang-options tr)})]
    [:div {:class (stl/css :option-list)}
     [:> ruby-hidden-option* common-props]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-size
                                               :default-value (cjk/enum-default :ruby-size)
                                               :label (tr "workspace.options.text-options.ruby-size")
                                               :options (:ruby-size options)})]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-align
                                               :default-value (cjk/enum-default :ruby-align)
                                               :label (tr "workspace.options.text-options.ruby-align")
                                               :options (:ruby-align options)})]
     [:> span-select-option* (mf/spread-props common-props
                                              {:attr :ruby-overhang
                                               :default-value (cjk/enum-default :ruby-overhang)
                                               :label (tr "workspace.options.text-options.ruby-overhang")
                                               :options (:ruby-overhang options)})]
     [:> position-select-option* (mf/spread-props common-props
                                                  {:attr :ruby-side
                                                   :label (tr "workspace.options.text-options.ruby-side")})]]))

(mf/defc ruby-advanced-options*
  "Reading input of a text selection, with its presentation options."
  [{:keys [values on-change on-blur labels vertical]}]
  (let [open*       (mf/use-state false)
        open?       (deref open*)
        toggle-open (mf/use-fn #(swap! open* not))
        common-props (ruby-common-props values on-change on-blur labels vertical)]
    [:div {:class (stl/css :ruby-advanced-options)}
     [:> ruby-options* (mf/spread-props common-props
                                        {:advanced-open     open?
                                         :on-toggle-advanced toggle-open})]
     [:> advanced-options* {:class      (stl/css :ruby-advanced-content)
                            :is-visible open?}
      [:> ruby-customization-options* common-props]]]))

(mf/defc ruby-presentation-options*
  "Presentation options of every reading of the texts, without a text
  selection."
  [{:keys [values on-change on-blur labels vertical]}]
  (let [open*        (mf/use-state false)
        open?        (deref open*)
        toggle-open  (mf/use-fn #(swap! open* not))
        common-props (ruby-common-props values on-change on-blur labels vertical)]
    [:div {:class (stl/css :ruby-advanced-options)}
     [:> title-bar* {:collapsable     true
                     :collapsed       (not open?)
                     :title           (:advanced labels)
                     :on-collapsed    toggle-open
                     :aria-expanded   open?
                     :data-testid     "ruby-presentation-options-toggle"}]
     [:> advanced-options* {:class      (stl/css :ruby-advanced-content)
                            :is-visible open?}
      [:> ruby-customization-options* common-props]]]))
