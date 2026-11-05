;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.text-language-layout
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
   [app.main.ui.workspace.sidebar.options.menus.text-japanese-layout :as tjl]
   [app.util.i18n :refer [tr]]
   [app.util.text.layout :as tl]
   [rumext.v2 :as mf]))

(defn korean-wrap-options
  [value translate]
  (cond-> [{:id "syllable" :label (translate "workspace.options.text-options.korean-wrap-syllable")}
           {:id "keep-words" :label (translate "workspace.options.text-options.korean-wrap-keep-words")}]
    (= value "spaces-only")
    (conj {:id "spaces-only" :label (translate "workspace.options.text-options.korean-wrap-spaces-only")})))

(mf/defc chinese-decoration-options*
  [{:keys [values on-change on-blur]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur
                         :vertical (tjl/vertical-japanese-layout? values)})]
    [:div {:class (stl/css :decoration-options)}
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :text-emphasis :default-value "none"
                              :label (tr "workspace.options.text-options.text-emphasis")
                              :options (tjl/text-emphasis-options tr)})]
     [:> tjl/position-select-option*
      (mf/spread-props props {:attr :text-emphasis-position
                              :label (tr "workspace.options.text-options.emphasis-position")})]
     [:> tjl/position-select-option*
      (mf/spread-props props {:attr :text-underline-position
                              :label (tr "workspace.options.text-options.underline-position")})]
     [:> tjl/span-select-option*
      (mf/spread-props props {:attr :annotation-clearance :default-value "none"
                              :label (tr "workspace.options.text-options.annotation-clearance")
                              :options (tjl/annotation-clearance-options)})]]))

(mf/defc chinese-reading-options*
  [{:keys [values ruby-values text-selection-active reading-selection-active zhuyin-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [zhuyin? (true? (:ruby-zhuyin values))
        labels {:input (if zhuyin? (tr "workspace.options.text-options.zhuyin") (tr "workspace.options.text-options.pinyin"))
                :placeholder (if zhuyin? (tr "workspace.options.text-options.zhuyin-placeholder") (tr "workspace.options.text-options.pinyin-placeholder"))
                :advanced (tr "workspace.options.text-options.pinyin-advanced-options")}
        handle-format-change (mf/use-fn
                              (mf/deps on-change)
                              (fn [value] (on-change {:ruby-zhuyin (= "zhuyin" value)})))
        props (mf/props {:values values :on-change on-change :on-blur on-blur
                         :vertical (tjl/vertical-japanese-layout? values)
                         :labels (cond-> labels zhuyin?
                                         (assoc :validate cjk/zhuyin-reading?
                                                :invalid (tr "workspace.options.text-options.zhuyin-invalid")))})]
    [:div {:class (stl/css :pinyin-options)}
     (when reading-selection-active
       [:div {:class (stl/css :reading-format-option)}
        [:span {:class (stl/css :option-label)} (tr "workspace.options.text-options.chinese-reading-format")]
        [:> select* {:default-selected (if zhuyin? "zhuyin" "pinyin")
                     :aria-label (tr "workspace.options.text-options.chinese-reading-format")
                     :options [{:id "pinyin" :label (tr "workspace.options.text-options.pinyin")}
                               {:id "zhuyin" :label (tr "workspace.options.text-options.zhuyin")
                                :disabled (not zhuyin-selection-active)}]
                     :on-change handle-format-change}]])
     (if (and reading-selection-active (or (not zhuyin?) zhuyin-selection-active))
       (if zhuyin?
         [:* [:p {:class (stl/css :selection-hint)} (tr "workspace.options.text-options.zhuyin-selection-hint")]
          [:> tjl/ruby-options* props]
          [:> tjl/ruby-hidden-option* props]]
         [:> tjl/ruby-advanced-options* props])
       [:*
        [:p {:class (stl/css :selection-hint)}
         (if zhuyin? (tr "workspace.options.text-options.zhuyin-selection-hint") (tr "workspace.options.text-options.pinyin-selection-hint"))]
        (when-not text-selection-active
          (if zhuyin?
            [:> tjl/ruby-hidden-option*
             (mf/spread-props props {:values ruby-values :on-change on-ruby-presentation-change})]
            [:> tjl/ruby-presentation-options*
             (mf/spread-props props {:values ruby-values
                                     :on-change on-ruby-presentation-change})]))])]))

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
    [:div {:class (stl/css :distribute-option)}
     [:span {:class (stl/css :option-label)} label]
     [:> switch* {:default-checked (= "distribute" (radio-selected (:text-align values)))
                  :aria-label      label
                  :on-change       handle-change}]]))

(mf/defc language-layout-options*
  "Options of the CJK `language` (`:japanese`, `:chinese` or `:korean`)."
  [{:keys [language values ruby-values text-selection-active reading-selection-active zhuyin-selection-active
           on-change on-ruby-presentation-change on-blur]}]
  (let [props (mf/props {:values values :on-change on-change :on-blur on-blur})]
    [:div {:class (stl/css :language-layout-options)}
     ;; Distribute goes below the writing mode row.
     (if (= :japanese language)
       [:> tjl/japanese-layout-options*
        (mf/spread-props props {:ruby-values ruby-values
                                :text-selection-active text-selection-active
                                :on-ruby-presentation-change on-ruby-presentation-change})
        [:> distribute-option* props]]
       [:*
        [:> tjl/writing-mode-controls* props]
        [:> distribute-option* props]])
     (when (= :chinese language)
       [:> chinese-decoration-options* props])
     (when (= :chinese language)
       [:> chinese-reading-options*
        (mf/spread-props props {:ruby-values ruby-values
                                :text-selection-active text-selection-active
                                :reading-selection-active reading-selection-active
                                :zhuyin-selection-active zhuyin-selection-active
                                :on-ruby-presentation-change on-ruby-presentation-change})])
     (when (= :korean language)
       [:> tjl/span-select-option*
        (mf/spread-props props
                         {:attr :korean-wrap
                          :default-value "syllable"
                          :label (tr "workspace.options.text-options.korean-wrap")
                          :options (korean-wrap-options (:korean-wrap values) tr)})])]))

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
