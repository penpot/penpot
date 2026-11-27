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
   [app.main.ui.workspace.sidebar.options.menus.text-cjk-controls :as tcc]
   [app.util.i18n :refer [tr]]
   [app.util.text.layout :as tl]
   [app.util.text.writing-mode :as wm]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Japanese
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn proportional-metrics-feature
  "Proportional metrics feature for the writing mode: `vpal` or `palt`."
  [writing-mode]
  (if (= writing-mode "vertical-rl") "vpal" "palt"))

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

;; Tate-chu-yoko (縦中横) for a whole text: none, or the digit runs up to a
;; length. `all` combines a text selection (see `combine-options`).
(defn tate-chu-yoko-options
  [translate]
  [{:value "none"
    :id    "none-text-combine-upright"
    :label (translate "workspace.options.text-options.text-combine-upright-none")
    :icon  i/text-combine-upright-none}
   {:value "digits"
    :id    "digits-text-combine-upright"
    :label (translate "workspace.options.text-options.text-combine-upright-digits")
    :icon  i/text-combine-upright-digits}])

(defn combine-options
  [translate]
  [{:value "none"
    :id    "none-combine-upright"
    :label (translate "workspace.options.text-options.text-combine-upright-none")
    :icon  i/text-combine-upright-none}
   {:value "all"
    :id    "all-combine-upright"
    :label (translate "workspace.options.text-options.text-combine-upright-all")
    :icon  i/text-combine-upright-all}])

(mf/defc tate-chu-yoko-buttons*
  "Tate-chu-yoko of vertical text, none or the digit runs, as a radio group
  of the writing mode row."
  [{:keys [value on-change on-blur]}]
  (let [options (mf/with-memo [] (tate-chu-yoko-options tr))]
    [:> tcc/attr-radio-options* {:values        {:text-combine-upright
                                                 (if (cjk/digit-combine? value) "digits" value)}
                                 :on-change     on-change
                                 :on-blur       on-blur
                                 :attr          :text-combine-upright
                                 :default-value (cjk/enum-default :text-combine-upright)
                                 :name          "text-combine-upright-options"
                                 :options       options}]))

(mf/defc combine-digits-option*
  "Longest digit run tate-chu-yoko sets upright, shown while it combines
  digits."
  [{:keys [value on-change on-blur]}]
  (let [label   (tr "workspace.options.text-options.text-combine-upright-digits-count")
        options (mf/with-memo [] (text-combine-upright-count-options tr))
        handle-change
        (mf/use-fn
         (mf/deps on-change on-blur)
         (fn [id]
           (on-change {:text-combine-upright id})
           (when (some? on-blur) (on-blur))))]
    (when (cjk/digit-combine? value)
      [:> tcc/option-row* {:label label}
       [:> select* {:default-selected value
                    :aria-label       label
                    :options          options
                    :on-change        handle-change}]])))

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

    [:> tcc/option-row* {:label label :switch true}
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
  {:placeholder (tr "workspace.options.text-options.ruby-placeholder")
   :advanced    (tr "workspace.options.text-options.ruby-advanced-options")
   :hidden      (tr "workspace.options.text-options.ruby-hidden")
   :shown       (tr "workspace.options.text-options.ruby-shown")})

(mf/defc japanese-selection-options*
  "Japanese options of a text selection: its reading, emphasis marks,
  warichu and, in vertical text, combine. `selection-key` names the
  selected range."
  [{:keys [values on-change on-blur selection-key reading-selection-active warichu-selection-active]}]
  (let [vertical? (tcc/vertical-writing? values)
        props     (mf/props {:values values :on-change on-change :on-blur on-blur
                             :vertical vertical?})
        labels    (mf/with-memo [] (japanese-reading-labels))
        options   (mf/with-memo []
                    {:warichu (warichu-options tr)
                     :combine (combine-options tr)})]
    [:*
     ;; A reading covers characters of one paragraph, and an existing
     ;; reading whole (`reading-selection?`), as in the canvas menu.
     (when ^boolean reading-selection-active
       [:> tcc/ruby-advanced-options* {:values        values
                                       :on-change     on-change
                                       :on-blur       on-blur
                                       :labels        labels
                                       :selection-key selection-key}])
     ;; Emphasis marks (圏点 / bouten) apply to the selected characters.
     [:> tcc/text-emphasis-option* props]
     [:> tcc/option-row* {:label (tr "workspace.options.text-options.warichu") :switch true}
      ;; As in the canvas menu: one span, or the whole of a note.
      [:> tcc/attr-radio-options* {:values        values
                                   :on-change     on-change
                                   :on-blur       on-blur
                                   :disabled      (not warichu-selection-active)
                                   :attr          :warichu
                                   :default-value (cjk/enum-default :warichu)
                                   :name          "warichu-options"
                                   :options       (:warichu options)}]]
     (when ^boolean vertical?
       [:> tcc/option-row* {:label (tr "workspace.options.text-options.combine-characters") :switch true}
        [:> tcc/attr-radio-options* {:values        {:text-combine-upright
                                                     (let [value (:text-combine-upright values)]
                                                       (cond
                                                         (tcc/mixed-span-value? value) :multiple
                                                         (= "all" value)               "all"
                                                         :else                         "none"))}
                                     :on-change     on-change
                                     :on-blur       on-blur
                                     :attr          :text-combine-upright
                                     :default-value (cjk/enum-default :text-combine-upright)
                                     :name          "combine-upright-options"
                                     :options       (:combine options)}]])]))

(mf/defc japanese-layout-options*
  "Japanese options of the whole text. Tate-chu-yoko itself is in the
  writing mode row; the digit count follows it."
  [{:keys [values on-change on-blur tcy-value on-tcy-change]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur})]
    [:*
     (when (tcc/vertical-writing? values)
       [:> combine-digits-option* {:value tcy-value :on-change on-tcy-change :on-blur on-blur}])
     [:> font-features-option* props]
     [:> line-adjustment-option* props]
     [:> tcc/annotation-clearance-option* props]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chinese
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- chinese-reading-labels
  [zhuyin?]
  (if zhuyin?
    {:placeholder (tr "workspace.options.text-options.zhuyin-placeholder")
     :validate    cjk/zhuyin-reading?
     :invalid     (tr "workspace.options.text-options.zhuyin-invalid")
     :hidden      (tr "workspace.options.text-options.zhuyin-hidden")
     :shown       (tr "workspace.options.text-options.zhuyin-shown")}
    {:placeholder (tr "workspace.options.text-options.pinyin-placeholder")
     :advanced    (tr "workspace.options.text-options.pinyin-advanced-options")
     :hidden      (tr "workspace.options.text-options.pinyin-hidden")
     :shown       (tr "workspace.options.text-options.pinyin-shown")}))

(defn reading-zhuyin?
  "Whether the reading of a Chinese text selection with `values` is
  zhuyin: a reading keeps its format; a selection without one, which the
  editor reports as not zhuyin, takes zhuyin when it can
  (`zhuyin-selection-active`) and the language defaults to it."
  [values zhuyin-selection-active]
  (let [stored (:ruby-zhuyin values)
        ruby   (:ruby values)
        ruby?  (not (or (nil? ruby) (= "" ruby)))]
    (cond
      (true? stored)                  true
      (tcc/mixed-span-value? stored)  false
      ruby?                           false
      :else (and (true? zhuyin-selection-active)
                 (cjk/zhuyin-default? (:text-language values))))))

(mf/defc chinese-selection-options*
  "Chinese options of a text selection: its reading, pinyin or zhuyin, when
  it can take one, then its emphasis marks. Zhuyin annotates one Han
  character at a time. `selection-key` names the selected range."
  [{:keys [values reading-selection-active zhuyin-selection-active selection-key on-change on-blur]}]
  (let [;; The format picked for this range, before it has a reading.
        chosen*      (mf/use-state nil)
        chosen       (deref chosen*)
        zhuyin?      (if (some? chosen)
                       (and (= "zhuyin" chosen) (true? zhuyin-selection-active))
                       (reading-zhuyin? values zhuyin-selection-active))
        stored?      (true? (:ruby-zhuyin values))
        vertical?    (tcc/vertical-writing? values)
        format-label (tr "workspace.options.text-options.chinese-reading-format")
        labels       (mf/with-memo [zhuyin?] (chinese-reading-labels zhuyin?))
        has-reading? (let [ruby (:ruby values)]
                       (and (string? ruby) (not (str/blank? ruby))
                            (not (tcc/mixed-span-value? ruby))))
        ;; The format of an existing reading changes at once; for a range
        ;; without one it waits for the reading typed next.
        handle-format-change
        (mf/use-fn
         (mf/deps on-change has-reading?)
         (fn [value]
           (reset! chosen* value)
           (when has-reading?
             (on-change {:ruby-zhuyin (= "zhuyin" value)}))))
        ;; A reading stores its format too: a reading without one is pinyin.
        ;; No reading, no format.
        handle-reading-change
        (mf/use-fn
         (mf/deps zhuyin? stored? on-change)
         (fn [attrs]
           (let [reading (:ruby attrs)]
             (on-change (cond-> attrs
                          (and (contains? attrs :ruby) (not (str/blank? reading))
                               (or zhuyin? stored?))
                          (assoc :ruby-zhuyin zhuyin?))))))
        reading-props (mf/props {:values values :on-change handle-reading-change :on-blur on-blur
                                 :labels labels :selection-key selection-key})
        props        (mf/props {:values values :on-change on-change :on-blur on-blur
                                :vertical vertical?})]

    (mf/with-effect [selection-key]
      (reset! chosen* nil))

    [:*
     (when ^boolean reading-selection-active
       [:*
        [:> tcc/option-row* {:label format-label}
         [:> select* {:default-selected (if zhuyin? "zhuyin" "pinyin")
                      :aria-label format-label
                      :options [{:id "pinyin" :label (tr "workspace.options.text-options.pinyin")}
                                {:id "zhuyin" :label (tr "workspace.options.text-options.zhuyin")
                                 :disabled (not zhuyin-selection-active)}]
                      :on-change handle-format-change}]]
        (cond
          (not zhuyin?)
          [:> tcc/ruby-advanced-options* reading-props]

          ^boolean zhuyin-selection-active
          [:> tcc/ruby-options* reading-props]

          :else
          [:p {:class (stl/css :selection-hint)}
           (tr "workspace.options.text-options.zhuyin-selection-hint")])])
     [:> tcc/text-emphasis-option* props]
     [:> tcc/position-select-option*
      (mf/spread-props props {:attr :text-emphasis-position
                              :label (tr "workspace.options.text-options.emphasis-position")})]]))

(mf/defc chinese-layout-options*
  "Chinese options of the whole text. Emphasis marks apply to a text
  selection, so they are not here."
  [{:keys [values on-change on-blur]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur
                         :vertical (tcc/vertical-writing? values)})]
    [:*
     [:> tcc/position-select-option*
      (mf/spread-props props {:attr :text-underline-position
                              :label (tr "workspace.options.text-options.underline-position")})]
     [:> tcc/annotation-clearance-option* props]]))

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
;; Ruby
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ruby-group-labels
  "Title and eye button labels of the readings group of a `family` text:
  furigana for Japanese; pinyin, zhuyin or, when the readings mix both
  (`zhuyin` is `:multiple`), readings for Chinese."
  [family zhuyin]
  (cond
    (not= family :chinese)
    {:title  (tr "workspace.options.text-options.language-group-ruby")
     :hidden (tr "workspace.options.text-options.ruby-hidden")
     :shown  (tr "workspace.options.text-options.ruby-shown")}

    (true? zhuyin)
    {:title  (tr "workspace.options.text-options.zhuyin")
     :hidden (tr "workspace.options.text-options.zhuyin-hidden")
     :shown  (tr "workspace.options.text-options.zhuyin-shown")}

    (= :multiple zhuyin)
    {:title  (tr "workspace.options.text-options.language-group-readings")
     :hidden (tr "workspace.options.text-options.readings-hidden")
     :shown  (tr "workspace.options.text-options.readings-shown")}

    :else
    {:title  (tr "workspace.options.text-options.pinyin")
     :hidden (tr "workspace.options.text-options.pinyin-hidden")
     :shown  (tr "workspace.options.text-options.pinyin-shown")}))

(mf/defc ruby-group-options*
  "How the readings of the text look. `values` are the ruby values of the
  whole text, and `on-change` applies to every reading of the text. Zhuyin
  readings can only be hidden."
  [{:keys [values on-change on-blur]}]
  (when-not (true? (:ruby-zhuyin values))
    [:> tcc/ruby-presentation-grid* {:values values
                                     :on-change on-change
                                     :on-blur on-blur}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Language panel and view
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private no-language "none")

(mf/defc language-picker*
  "The language of the texts, as a select row opening the profile list:
  None first, then the profiles grouped by language. `on-select` receives
  the chosen tag, nil for None."
  {::mf/private true}
  [{:keys [language on-select children]}]
  (let [dropdown*     (mf/use-state false)
        dropdown?     (deref dropdown*)
        container-ref (mf/use-ref nil)
        options       (mf/with-memo [language]
                        (into [{:id no-language
                                :label (tr "workspace.options.text-options.language-none")
                                :selected (nil? language)}]
                              (tl/language-options language)))

        toggle-dropdown (mf/use-fn #(swap! dropdown* not))
        close-dropdown  (mf/use-fn #(reset! dropdown* false))

        handle-select
        (mf/use-fn
         (mf/deps on-select)
         (fn [tag _event]
           (reset! dropdown* false)
           (on-select (when (not= no-language tag) tag))))]

    [:div {:class (stl/css :language-row) :ref container-ref}
     [:button {:type "button"
               :class (stl/css-case :language-select true
                                    :mixed (= :multiple language))
               :aria-haspopup "listbox"
               :aria-expanded dropdown?
               :aria-label (tr "workspace.options.text-options.language")
               :data-testid "text-language-select"
               :on-click toggle-dropdown}
      [:span {:class (stl/css :language-name)} (tl/language-label language)]
      [:> icon* {:icon-id i/arrow-down
                 :class (stl/css :language-arrow)
                 :size "s"
                 :aria-hidden true}]]
     children
     [:> grouped-dropdown* {:show dropdown?
                            :on-close close-dropdown
                            :container container-ref
                            :class (stl/css :language-dropdown)
                            :options options
                            :on-select handle-select}]]))

(defn- use-select-language
  "Handler giving the texts with the option `values` the language `tag`
  (nil removes it); new texts take it too. `on-chosen` runs after a CJK
  language is chosen."
  [on-change on-chosen values]
  (mf/use-fn
   (mf/deps on-change on-chosen values)
   (fn [tag]
     (on-change (cjk/chosen-language-attrs tag values))
     (st/emit! (dwt/remember-text-language tag))
     (when (and (some? on-chosen) (some? (cjk/language-family tag)))
       (on-chosen)))))

(mf/defc language-section*
  "Language panel of the text options. A text has one language: picking or
  removing one changes every paragraph of the texts in `ids`, and new texts
  take it. The options of the language open in their own view through
  `on-open-view`, offered when the texts share a CJK language, and also
  right after one is chosen."
  [{:keys [ids values on-change on-open-view]}]
  (let [language-ref  (mf/with-memo [ids] (refs/texts-language ids))
        language      (mf/deref language-ref)
        family        (cjk/language-family language)

        open*         (mf/use-state true)
        open?         (deref open*)
        dropdown*     (mf/use-state false)
        dropdown?     (deref dropdown*)
        container-ref (mf/use-ref nil)

        title         (tr "workspace.options.text-options.language")
        options       (mf/with-memo [] (tl/language-options nil))

        toggle-open     (mf/use-fn #(swap! open* not))
        toggle-dropdown (mf/use-fn #(swap! dropdown* not))
        close-dropdown  (mf/use-fn #(reset! dropdown* false))

        select-language (use-select-language on-change on-open-view values)

        handle-add
        (mf/use-fn
         (mf/deps select-language)
         (fn [tag _event]
           (reset! dropdown* false)
           (reset! open* true)
           (select-language tag)))]

    [:section {:class (stl/css :language-section)
               :data-keep-editing-on-blur true
               :aria-label title}
     [:div {:class (stl/css :language-title) :ref container-ref}
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
        [:> grouped-dropdown* {:show dropdown?
                               :on-close close-dropdown
                               :container container-ref
                               :class (stl/css :language-dropdown)
                               :options options
                               :on-select handle-add}])]

     (when (and (some? language) open?)
       [:div {:class (stl/css :language-content)}
        [:> language-picker* {:language language :on-select select-language}
         (when (some? on-open-view)
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.text-options.language-options")
                             :data-testid "text-language-options"
                             :disabled (nil? family)
                             :on-click on-open-view
                             :icon i/arrow-right}])]])]))

(mf/defc language-group*
  "Group of the language view under a divider: a `title`, the buttons in
  `actions` at its end, and the rows in `children`."
  {::mf/private true}
  [{:keys [title actions children]}]
  [:div {:class (stl/css :language-group)}
   [:div {:class (stl/css :language-group-header)}
    [:h3 {:class (stl/css :language-group-title)} title]
    actions]
   children])

(mf/defc language-view*
  "Options of the CJK language of the texts in `ids`, in a view of their
  own that `on-close` leaves. Selected text (with a text selection) comes
  first, then Layout, then the readings of the whole text when the text
  has some (`has-ruby`) and no text is selected. Layout starts with the
  writing mode row of the Text section, with tate-chu-yoko for Japanese,
  so the view holds every option of the language.
  The view closes when the texts no longer share a CJK language."
  [{:keys [ids options-props on-change on-close has-ruby]}]
  (let [language-ref (mf/with-memo [ids] (refs/texts-language ids))
        language     (mf/deref language-ref)
        family       (cjk/language-family language)

        values                      (unchecked-get options-props "values")
        ruby-values                 (unchecked-get options-props "rubyValues")
        text-selection-active       (unchecked-get options-props "textSelectionActive")
        on-blur                     (unchecked-get options-props "onBlur")
        on-ruby-presentation-change (unchecked-get options-props "onRubyPresentationChange")

        select-language (use-select-language on-change nil values)

        ;; Whole-text tate-chu-yoko leaves the spans combined as a whole to
        ;; the text selection.
        shapes-ref    (mf/with-memo [ids] (refs/objects-by-id ids))
        shapes        (mf/deref shapes-ref)
        tcy-value     (mf/with-memo [shapes values]
                        (if (seq shapes)
                          (dwt/whole-text-combine-value shapes)
                          (:text-combine-upright values)))
        on-tcy-change (mf/use-fn
                       (mf/deps ids)
                       (fn [{:keys [text-combine-upright]}]
                         (st/emit! (dwt/update-text-combine-digits ids text-combine-upright))))

        ;; The Layout group sets the whole text, also in edit mode, where
        ;; span attrs would go to the caret or the selected range only.
        on-layout-change
        (mf/use-fn
         (mf/deps ids on-change)
         (fn [attrs]
           (let [span-attrs (select-keys attrs cjk/span-attrs)
                 attrs      (apply dissoc attrs cjk/span-attrs)]
             (when (seq attrs)
               (on-change attrs))
             (when (seq span-attrs)
               (st/emit! (dwt/update-all-spans ids span-attrs))))))
        layout-props (mf/spread-props options-props {:on-change on-layout-change})

        ruby-labels (ruby-group-labels family (:ruby-zhuyin ruby-values))]

    (mf/with-effect [family]
      (when (nil? family)
        (on-close)))

    [:section {:class (stl/css :language-view)
               :data-keep-editing-on-blur true
               :aria-label (tr "workspace.options.text-options.language-options")}
     [:div {:class (stl/css :language-view-header)}
      [:> icon-button* {:variant "ghost"
                        :aria-label (tr "labels.back")
                        :data-testid "text-language-back"
                        :on-click on-close
                        :icon i/arrow-left}]
      [:span {:class (stl/css :language-view-title)}
       (tr "workspace.options.text-options.language")]]

     [:> language-picker* {:language language :on-select select-language}]

     (when (and ^boolean text-selection-active (not= :korean family))
       [:> language-group* {:title (tr "workspace.options.text-options.language-group-selected-text")}
        (case family
          :japanese [:> japanese-selection-options* options-props]
          :chinese  [:> chinese-selection-options* options-props]
          nil)])

     (when (some? family)
       [:> language-group* {:title (tr "workspace.options.text-options.language-group-layout")}
        (when ^boolean (wm/vertical-layout-active?)
          [:> tcc/writing-mode-row* {:values values :on-change on-change :on-blur on-blur}
           (when (and (= :japanese family) (tcc/vertical-writing? values))
             [:> tate-chu-yoko-buttons* {:value tcy-value :on-change on-tcy-change :on-blur on-blur}])])
        (case family
          :japanese [:> japanese-layout-options*
                     (mf/spread-props layout-props {:tcy-value tcy-value :on-tcy-change on-tcy-change})]
          :chinese  [:> chinese-layout-options* layout-props]
          :korean   [:> korean-layout-options* options-props])])

     (when (and ^boolean has-ruby
                (not ^boolean text-selection-active)
                (not= :korean family)
                (some? family))
       [:> language-group*
        {:title   (:title ruby-labels)
         :actions (mf/html
                   [:> tcc/ruby-visibility-button* {:values ruby-values
                                                    :labels ruby-labels
                                                    :on-change on-ruby-presentation-change
                                                    :on-blur on-blur}])}
        [:> ruby-group-options* {:values ruby-values
                                 :on-change on-ruby-presentation-change
                                 :on-blur on-blur}]])]))
