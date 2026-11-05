;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.text-language-layout
  "Language panel of the text options, and the options of each CJK
  language below it. The controls they share are in `text-cjk-controls`."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.grouped-dropdown :refer [grouped-dropdown*]]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.ds.controls.switch :refer [switch*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.workspace.sidebar.options.common :refer [radio-selected]]
   [app.main.ui.workspace.sidebar.options.menus.text-cjk-controls :as tcc]
   [app.util.i18n :refer [tr]]
   [app.util.text.layout :as tl]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Japanese
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;; select* matches options by :id, so each id is the stored value.
(defn text-combine-upright-count-options
  [translate]
  [{:id    "digits2"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-2")}
   {:id    "digits3"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-3")}
   {:id    "digits"
    :label (translate "workspace.options.text-options.text-combine-upright-digits-4")}])

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

(defn- text-combine-upright-value
  [values]
  (radio-selected (:text-combine-upright values) (cjk/enum-default :text-combine-upright)))

(mf/defc japanese-writing-mode-options*
  "Radio groups Japanese adds to the writing mode row: tate-chu-yoko for
  vertical text, warichu for a text selection. Digit tate-chu-yoko finds
  eligible runs itself, so it applies to a whole shape; `all` is offered
  only for a text selection."
  [{:keys [values on-change on-blur text-selection-active]}]
  (let [props   (mf/props {:on-change on-change :on-blur on-blur})
        value   (:text-combine-upright values)
        ;; The digit counts are picked in their own select.
        tcy     (if (cjk/digit-combine? (radio-selected value)) "digits" value)
        options (mf/with-memo [text-selection-active]
                  {:text-combine-upright (text-combine-upright-options text-selection-active tr)
                   :warichu              (warichu-options tr)})]
    [:*
     (when (tcc/vertical-writing? values)
       [:> tcc/attr-radio-options* (mf/spread-props props
                                                    {:values        {:text-combine-upright tcy}
                                                     :attr          :text-combine-upright
                                                     :default-value (cjk/enum-default :text-combine-upright)
                                                     :name          "text-combine-upright-options"
                                                     :options       (:text-combine-upright options)})])
     (when ^boolean text-selection-active
       [:> tcc/attr-radio-options* (mf/spread-props props
                                                    {:values        values
                                                     :attr          :warichu
                                                     :default-value (cjk/enum-default :warichu)
                                                     :name          "warichu-options"
                                                     :options       (:warichu options)})])]))

(mf/defc text-combine-upright-count-option*
  [{:keys [values on-change on-blur]}]
  (let [value   (text-combine-upright-value values)
        options (mf/with-memo [] (text-combine-upright-count-options tr))]
    (when (cjk/digit-combine? value)
      [:> tcc/span-select-option* {:values        {:text-combine-upright value}
                                   :on-change     on-change
                                   :on-blur       on-blur
                                   :attr          :text-combine-upright
                                   :default-value value
                                   :label         (tr "workspace.options.text-options.text-combine-upright-digits-count")
                                   :options       options}])))

(mf/defc font-features-option*
  "Proportional metric alternates, per span: `palt` for horizontal text,
  `vpal` for vertical."
  [{:keys [values on-change on-blur]}]
  (let [feature  (proportional-metrics-feature (:writing-mode values))
        value    (:font-features values)
        enabled? (cond
                   (tcc/mixed-span-value? value) nil
                   (= feature value) true
                   :else false)
        label    (tr "workspace.options.text-options.font-features")

        handle-change
        (mf/use-fn
         (mf/deps feature on-change on-blur)
         (fn [checked?]
           (on-change {:font-features (if checked? feature "none")})
           (when (some? on-blur) (on-blur))))]

    [:> tcc/option-row* {:label label}
     [:> switch* {:default-checked enabled?
                  :aria-label      label
                  :on-change       handle-change}]]))

(mf/defc line-adjustment-option*
  "Whole-shape setting: how lines slightly too long are fitted."
  [{:keys [values on-change on-blur]}]
  (let [options (mf/with-memo [] (line-adjustment-options))]
    [:> tcc/span-select-option* {:values        values
                                 :on-change     on-change
                                 :on-blur       on-blur
                                 :attr          :line-adjustment
                                 :default-value (cjk/enum-default :line-adjustment)
                                 :label         (tr "workspace.options.text-options.line-adjustment")
                                 :options       options}]))

(defn- japanese-reading-labels
  []
  {:input       (tr "workspace.options.text-options.ruby")
   :placeholder (tr "workspace.options.text-options.ruby-placeholder")
   :advanced    (tr "workspace.options.text-options.ruby-advanced-options")})

(mf/defc japanese-layout-options*
  "Japanese options below the writing mode row."
  [{:keys [values ruby-values text-selection-active on-change on-ruby-presentation-change on-blur]}]
  (let [vertical? (tcc/vertical-writing? values)
        labels    (japanese-reading-labels)
        props     (mf/props {:values values :on-change on-change :on-blur on-blur
                             :vertical vertical? :labels labels})]
    [:*
     (when ^boolean vertical?
       [:> text-combine-upright-count-option* props])
     [:> font-features-option* props]
     [:> line-adjustment-option* props]
     ;; Emphasis marks (圏点 / bouten) apply to the selected characters.
     (when ^boolean text-selection-active
       [:> tcc/text-emphasis-option* props])
     [:> tcc/annotation-clearance-option* props]
     (if ^boolean text-selection-active
       [:> tcc/ruby-advanced-options* props]
       [:> tcc/ruby-presentation-options*
        (mf/spread-props props {:values ruby-values
                                :on-change on-ruby-presentation-change})])]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chinese
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc chinese-decoration-options*
  [{:keys [values on-change on-blur]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur
                         :vertical (tcc/vertical-writing? values)})]
    [:*
     [:> tcc/text-emphasis-option* props]
     [:> tcc/position-select-option*
      (mf/spread-props props {:attr :text-emphasis-position
                              :label (tr "workspace.options.text-options.emphasis-position")})]
     [:> tcc/position-select-option*
      (mf/spread-props props {:attr :text-underline-position
                              :label (tr "workspace.options.text-options.underline-position")})]
     [:> tcc/annotation-clearance-option* props]]))

(defn- chinese-reading-labels
  [zhuyin?]
  (if zhuyin?
    {:input       (tr "workspace.options.text-options.zhuyin")
     :placeholder (tr "workspace.options.text-options.zhuyin-placeholder")
     :advanced    (tr "workspace.options.text-options.pinyin-advanced-options")
     :validate    cjk/zhuyin-reading?
     :invalid     (tr "workspace.options.text-options.zhuyin-invalid")}
    {:input       (tr "workspace.options.text-options.pinyin")
     :placeholder (tr "workspace.options.text-options.pinyin-placeholder")
     :advanced    (tr "workspace.options.text-options.pinyin-advanced-options")}))

(mf/defc chinese-reading-options*
  "Pinyin or zhuyin readings. Zhuyin annotates one Han character at a time."
  [{:keys [values ruby-values text-selection-active reading-selection-active zhuyin-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [zhuyin? (true? (:ruby-zhuyin values))
        format-label (tr "workspace.options.text-options.chinese-reading-format")
        handle-format-change (mf/use-fn
                              (mf/deps on-change)
                              (fn [value] (on-change {:ruby-zhuyin (= "zhuyin" value)})))
        props (mf/props {:values values :on-change on-change :on-blur on-blur
                         :vertical (tcc/vertical-writing? values)
                         :labels (chinese-reading-labels zhuyin?)})
        presentation-props (mf/spread-props props {:values ruby-values
                                                   :on-change on-ruby-presentation-change})]
    [:*
     (when reading-selection-active
       [:> tcc/option-row* {:label format-label}
        [:> select* {:default-selected (if zhuyin? "zhuyin" "pinyin")
                     :aria-label format-label
                     :options [{:id "pinyin" :label (tr "workspace.options.text-options.pinyin")}
                               {:id "zhuyin" :label (tr "workspace.options.text-options.zhuyin")
                                :disabled (not zhuyin-selection-active)}]
                     :on-change handle-format-change}]])
     (if (and reading-selection-active (or (not zhuyin?) zhuyin-selection-active))
       (if zhuyin?
         [:* [:p {:class (stl/css :selection-hint)} (tr "workspace.options.text-options.zhuyin-selection-hint")]
          [:> tcc/ruby-options* props]
          [:> tcc/ruby-hidden-option* props]]
         [:> tcc/ruby-advanced-options* props])
       [:*
        [:p {:class (stl/css :selection-hint)}
         (if zhuyin? (tr "workspace.options.text-options.zhuyin-selection-hint") (tr "workspace.options.text-options.pinyin-selection-hint"))]
        (when-not text-selection-active
          (if zhuyin?
            [:> tcc/ruby-hidden-option* presentation-props]
            [:> tcc/ruby-presentation-options* presentation-props]))])]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Korean
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn korean-wrap-options
  [value translate]
  (cond-> [{:id "syllable" :label (translate "workspace.options.text-options.korean-wrap-syllable")}
           {:id "keep-words" :label (translate "workspace.options.text-options.korean-wrap-keep-words")}]
    (= value "spaces-only")
    (conj {:id "spaces-only" :label (translate "workspace.options.text-options.korean-wrap-spaces-only")})))

(mf/defc korean-layout-options*
  [{:keys [values on-change on-blur]}]
  [:> tcc/span-select-option* {:values        values
                               :on-change     on-change
                               :on-blur       on-blur
                               :attr          :korean-wrap
                               :default-value (cjk/enum-default :korean-wrap)
                               :label         (tr "workspace.options.text-options.korean-wrap")
                               :options       (korean-wrap-options (:korean-wrap values) tr)}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Language panel
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(mf/defc distribute-option*
  "Distributed alignment (均等割り付け, 分散对齐, 배분 정렬): spreads the
  characters of every line over the box. Turning it off aligns the text to
  the start."
  [{:keys [values on-change on-blur]}]
  (let [label (tr "workspace.options.text-options.text-align-distribute")
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [checked?]
           (on-change {:text-align (if checked? "distribute" "left")})
           (when (some? on-blur) (on-blur))))]
    [:> tcc/option-row* {:label label}
     [:> switch* {:default-checked (= "distribute" (radio-selected (:text-align values)))
                  :aria-label      label
                  :on-change       handle-change}]]))

(mf/defc language-layout-options*
  "Options of the CJK `language` (`:japanese`, `:chinese` or `:korean`):
  the writing mode row and Distribute, which all share, then the options
  of the language."
  [{:keys [language values text-selection-active on-change on-blur] :as props}]
  [:div {:class (stl/css :language-layout-options)}
   [:> tcc/writing-mode-controls* {:values values :on-change on-change :on-blur on-blur}
    (when (= :japanese language)
      [:> japanese-writing-mode-options* {:values values
                                          :on-change on-change
                                          :on-blur on-blur
                                          :text-selection-active text-selection-active}])]
   [:> distribute-option* props]
   (case language
     :japanese [:> japanese-layout-options* props]
     :chinese  [:*
                [:> chinese-decoration-options* props]
                [:> chinese-reading-options* props]]
     :korean   [:> korean-layout-options* props]
     nil)])

(mf/defc language-row*
  "The language of the texts, opening the language dropdown, and the button
  removing it when the texts have a language of their own."
  {::mf/private true}
  [{:keys [language inherited open on-toggle on-remove children]}]
  [:div {:class (stl/css :language-row)}
   [:button {:type "button"
             :class (stl/css-case :language-select true
                                  :mixed (= :multiple language))
             :aria-haspopup "listbox"
             :aria-expanded open
             :aria-label (tr "workspace.options.text-options.language")
             :data-testid "text-language-select"
             :on-click on-toggle}
    [:span {:class (stl/css :language-name)} (tl/language-label language)]
    [:> icon* {:icon-id i/arrow-down
               :class (stl/css :language-arrow)
               :size "s"
               :aria-hidden true}]]
   (when-not ^boolean inherited
     [:> icon-button* {:variant "ghost"
                       :aria-label (tr "workspace.options.text-options.remove-language")
                       :data-testid "text-language-remove"
                       :on-click on-remove
                       :icon i/remove}])
   children])

(mf/defc language-section*
  "Language panel of the text options. A text has one language: picking or
  removing one changes every paragraph of the texts in `ids`, and new texts
  take it. A text without language takes the file one. The options of the
  language show once it is the same for all of them."
  [{:keys [ids options-props on-change]}]
  (let [language-ref  (mf/with-memo [ids] (refs/texts-language ids))
        {:keys [language own]} (mf/deref language-ref)
        family        (cjk/language-family language)
        inherited     (and (some? language) (nil? own))

        open*         (mf/use-state true)
        open?         (deref open*)
        dropdown*     (mf/use-state false)
        dropdown?     (deref dropdown*)
        container-ref (mf/use-ref nil)

        title         (tr "workspace.options.text-options.language")
        options       (mf/with-memo [language] (tl/language-options language))

        toggle-open
        (mf/use-fn #(swap! open* not))

        toggle-dropdown
        (mf/use-fn #(swap! dropdown* not))

        close-dropdown
        (mf/use-fn #(reset! dropdown* false))

        handle-select
        (mf/use-fn
         (mf/deps on-change)
         (fn [tag _event]
           (reset! dropdown* false)
           (reset! open* true)
           (on-change {:text-language tag})
           (st/emit! (dwt/remember-text-language tag))))

        handle-remove
        (mf/use-fn
         (mf/deps on-change)
         (fn [_event]
           (reset! dropdown* false)
           (on-change {:text-language nil})
           (st/emit! (dwt/remember-text-language nil))))

        ;; Opens below the control that toggles it: the add button of the
        ;; title, or the language row.
        dropdown
        (mf/html
         [:> grouped-dropdown* {:show dropdown?
                                :on-close close-dropdown
                                :container container-ref
                                :options options
                                :on-select handle-select}])]

    [:section {:class (stl/css :language-section)
               :data-keep-editing-on-blur true
               :aria-label title
               :ref container-ref}
     [:div {:class (stl/css :language-title)}
      [:> title-bar* {:collapsable (some? language)
                      :collapsed (not open?)
                      :on-collapsed toggle-open
                      :title title}
       (when (nil? language)
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.text-options.add-language")
                           :aria-haspopup "listbox"
                           :aria-expanded dropdown?
                           :data-testid "text-language-add"
                           :on-click toggle-dropdown
                           :icon i/add}])]
      (when (nil? language)
        dropdown)]

     (when (and (some? language) open?)
       [:div {:class (stl/css :language-content)}
        [:> language-row* {:language language
                           :inherited inherited
                           :open dropdown?
                           :on-toggle toggle-dropdown
                           :on-remove handle-remove}
         dropdown]
        (when (some? family)
          [:> language-layout-options*
           (mf/spread-props options-props {:language family})])])]))
