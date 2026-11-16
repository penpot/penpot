;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.text-options-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.types.text.cjk-layout :as cjk]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.ui.ds.controls.select :as select]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.text.html-text :as html-text]
   [app.main.ui.shapes.text.styles :as text-styles]
   [app.main.ui.workspace.sidebar.options.common :refer [radio-selected]]
   [app.main.ui.workspace.sidebar.options.menus.text :as text-menu]
   [app.main.ui.workspace.sidebar.options.menus.text-cjk-controls :as tcc]
   [app.main.ui.workspace.sidebar.options.menus.text-language-layout :as tll]
   [app.util.text.content :as tc]
   [app.util.text.layout :as tl]
   [app.util.text.writing-mode :as wm]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private japanese-span-attrs
  [:text-combine-upright
   :text-emphasis
   :ruby
   :ruby-hidden
   :ruby-size
   :ruby-align
   :ruby-overhang
   :ruby-side
   :warichu
   :font-features
   :annotation-clearance])


(defn- text-content
  "Text content with one paragraph per language in `languages`; nil leaves
  the paragraph without language."
  [& languages]
  {:type "root"
   :children
   [{:type "paragraph-set"
     :children
     (mapv (fn [language]
             (cond-> {:type "paragraph" :children [{:text "文"}]}
               (some? language) (assoc :text-language language)))
           languages)}]})

(defn- render-language-section
  "Static markup of the Language panel for texts with `language`."
  [language]
  (with-redefs [refs/texts-language (fn [_] (l/atom language))
                hooks/use-portal-container (fn ([] nil) ([_] nil))]
    (rds/renderToStaticMarkup
     (mf/element tll/language-section*
                 (mf/props {:ids [(random-uuid)]
                            :on-change identity
                            :on-open-view identity})))))

(defn- render-language-view
  "Static markup of the language options view for texts with `language`."
  [language {:keys [values ruby-values selection reading has-ruby]}]
  (with-redefs [refs/texts-language (fn [_] (l/atom language))
                hooks/use-portal-container (fn ([] nil) ([_] nil))]
    (rds/renderToStaticMarkup
     (mf/element tll/language-view*
                 (mf/props {:ids [(random-uuid)]
                            :options-props (mf/props {:values (merge {:text-language language} values)
                                                      :ruby-values ruby-values
                                                      :text-selection-active (boolean selection)
                                                      :reading-selection-active (boolean reading)
                                                      :zhuyin-selection-active false
                                                      :on-change identity
                                                      :on-ruby-presentation-change identity})
                            :on-change identity
                            :on-close identity
                            :has-ruby (boolean has-ruby)})))))

(t/deftest texts-language-is-mixed-when-any-paragraph-differs
  (t/is (= "ja" (tl/get-texts-language [(text-content "ja" "ja") (text-content "ja")])))
  (t/is (nil? (tl/get-texts-language [(text-content nil nil)])))
  (t/is (nil? (tl/get-texts-language [])))
  (t/is (= :multiple (tl/get-texts-language [(text-content "ja" nil)]))
        "a paragraph without language differs from one with it")
  (t/is (= :multiple (tl/get-texts-language [(text-content "ko") (text-content "zh-Hant-TW")])))
  (t/is (= :multiple (tl/get-texts-language [(text-content "ko") (text-content nil)])))
  (t/is (= :multiple (tl/get-texts-language [(text-content "ko") nil]))
        "a text that has no content yet has no language"))

(t/deftest language-dropdown-groups-the-regional-languages
  (let [options (tl/language-options "zh-Hant-TW")]
    (t/is (= ["japanese" "ja" "chinese"
              "zh-Hans-CN" "zh-Hant-CN" "zh-Hant-HK" "zh-Hant-TW"
              "korean" "ko"]
             (mapv :id options)))
    (t/is (= ["japanese" "chinese" "korean"] (mapv :id (filter :header options))))
    (t/is (= ["zh-Hant-TW"] (mapv :id (filter :selected options))))
    (t/is (not-any? :detail options) "the list shows no language tags")
    (t/is (not-any? :selected (tl/language-options :multiple)))))

(t/deftest language-row-names-mixed-and-unlisted-languages
  (t/is (= "settings.multiple" (tl/language-label :multiple)))
  (t/is (= "workspace.options.text-options.language-japanese" (tl/language-label "ja")))
  (t/is (= "workspace.options.text-options.language-chinese-taiwan" (tl/language-label "zh-Hant-TW"))
        "a Chinese profile has one full name everywhere")
  (t/is (= "zh-Hans" (tl/language-label "zh-Hans"))
        "a tag outside the list shows as it is"))

(t/deftest language-panel-without-language-only-offers-to-add-one
  (let [markup (render-language-section nil)]
    (t/is (str/includes? markup "data-testid=\"text-language-add\""))
    (t/is (not (str/includes? markup "data-testid=\"text-language-select\"")))
    (t/is (not (str/includes? markup "horizontal-tb-writing-mode"))
          "writing mode needs a language")))

(t/deftest language-panel-offers-the-options-in-their-own-view
  (doseq [language ["ja" "ko" "zh-Hant-HK"]]
    (let [markup (render-language-section language)]
      (t/is (not (str/includes? markup "data-testid=\"text-language-add\"")))
      (t/is (str/includes? markup "data-testid=\"text-language-select\""))
      (t/is (str/includes? markup "data-testid=\"text-language-options\""))
      (t/is (not (str/includes? markup "disabled=\"\"")))
      (t/is (not (str/includes? markup "data-testid=\"text-language-remove\""))
            "None in the list removes the language")
      (t/is (not (str/includes? markup "workspace.options.text-options.line-adjustment"))
            "the options are in the view, not in the main sidebar"))))

(t/deftest mixed-languages-disable-the-options-view
  (let [markup (render-language-section :multiple)]
    (t/is (str/includes? markup "settings.multiple"))
    (t/is (str/includes? markup "disabled=\"\""))
    (t/is (not (str/includes? markup "data-testid=\"text-language-add\"")))))

(t/deftest new-texts-take-the-last-language-else-the-file-one
  (let [language (fn [remembered file]
                   (with-redefs [refs/default-font (l/atom nil)
                                 refs/default-text-language (l/atom remembered)
                                 refs/file-text-language (l/atom file)]
                     (get-in (tc/v2-default-text-content)
                             [:children 0 :children 0 :text-language])))]
    (t/is (= "ja" (language nil "ja")) "the file language seeds a new text")
    (t/is (= "ko" (language "ko" "ja")) "the language used last wins")
    (t/is (nil? (language nil nil)))))

(t/deftest korean-word-wrapping-is-distinct-from-the-saved-experiment
  (t/is (= ["syllable" "keep-words"]
           (mapv :id (tll/korean-wrap-options nil identity))))
  (t/is (= ["syllable" "keep-words" "spaces-only"]
           (mapv :id (tll/korean-wrap-options "spaces-only" identity)))))

(t/deftest language-view-shows-the-layout-options-of-the-language
  (doseq [[language label] [["ja" "workspace.options.text-options.line-adjustment"]
                            ["ko" "workspace.options.text-options.korean-wrap"]
                            ["zh-Hant-TW" "workspace.options.text-options.underline-position"]]]
    (let [markup (render-language-view language {})]
      (t/is (str/includes? markup "data-testid=\"text-language-back\""))
      (t/is (str/includes? markup "workspace.options.text-options.language-group-layout"))
      (t/is (str/includes? markup label) language))))

(t/deftest language-view-shows-selected-text-options-only-for-a-selection
  (doseq [language ["ja" "zh-Hant-TW"]]
    (let [group "workspace.options.text-options.language-group-selected-text"
          emphasis "workspace.options.text-options.text-emphasis"]
      (t/is (not (str/includes? (render-language-view language {}) group)))
      (t/is (str/includes? (render-language-view language {:selection true}) group))
      (t/is (str/includes? (render-language-view language {:selection true}) emphasis))))
  (t/is (not (str/includes? (render-language-view "ja" {})
                            "workspace.options.text-options.text-emphasis"))
        "Japanese emphasis marks need a text selection")
  (t/is (< (str/index-of (render-language-view "ja" {:selection true})
                         "workspace.options.text-options.language-group-selected-text")
           (str/index-of (render-language-view "ja" {:selection true})
                         "workspace.options.text-options.language-group-layout"))
        "Selected text comes first"))

(t/deftest language-view-shows-ruby-options-only-for-text-with-ruby
  (let [group "workspace.options.text-options.language-group-ruby"]
    (t/is (not (str/includes? (render-language-view "ja" {}) group)))
    (t/is (not (str/includes? (render-language-view "ja" {}) "workspace.options.text-options.annotation-clearance"))
          "clearance shows with the readings")
    (let [markup (render-language-view "ja" {:has-ruby true})]
      (t/is (str/includes? markup group))
      (doseq [label ["annotation-clearance" "ruby-size" "ruby-align" "ruby-overhang" "ruby-side" "ruby-hidden"]]
        (t/is (str/includes? markup (str "workspace.options.text-options." label)) label)))
    (t/is (not (str/includes? (render-language-view "ko" {:has-ruby true}) group)))))

(defn- count-of
  "Times the translation key `text` shows in `markup`, as a whole key."
  [markup text]
  (count (re-seq (js/RegExp. (str (str/replace text "." "\\.") "(?![-\\w])") "g")
                 markup)))

(t/deftest chinese-layout-keeps-emphasis-and-clearance-of-the-whole-text
  (let [emphasis  "workspace.options.text-options.text-emphasis"
        position  "workspace.options.text-options.emphasis-position"
        clearance "workspace.options.text-options.annotation-clearance"
        whole     (render-language-view "zh-Hant-TW" {})
        selection (render-language-view "zh-Hant-TW" {:selection true})]
    (t/is (pos? (count-of whole emphasis)) "emphasis marks apply to the whole text")
    (t/is (str/includes? whole position))
    (t/is (str/includes? whole clearance) "clearance also spaces emphasis marks")
    (t/is (= (count-of whole emphasis) (count-of selection emphasis))
          "with a selection, emphasis marks show once, for the selection")
    (t/is (= (count-of (render-language-view "ja" {:has-ruby true}) clearance)
             (count-of (render-language-view "zh-Hant-TW" {:has-ruby true}) clearance))
          "with readings, clearance shows once, in the Ruby group")))

(t/deftest zhuyin-explains-what-selection-it-needs
  (let [hint "workspace.options.text-options.zhuyin-selection-hint"]
    (t/is (str/includes? (render-language-view "zh-Hant-TW" {:values {:ruby-zhuyin true}
                                                             :selection true
                                                             :reading true})
                         hint))
    (t/is (not (str/includes? (render-language-view "zh-Hant-TW" {:selection true
                                                                  :reading true})
                              hint)))))

(t/deftest chinese-reading-input-needs-a-reading-selection
  (doseq [reading [false true]]
    (let [markup (render-language-view "zh-Hant-TW" {:values {:ruby "nǐ hǎo"}
                                                     :selection true
                                                     :reading reading})]
      (t/is (= reading (str/includes? markup "workspace.options.text-options.pinyin-placeholder")))
      (t/is (not (str/includes? markup "selection-hint")) "no permanent hint"))))

(t/deftest text-emphasis-select-reports-and-restores-canonical-values
  (let [options     (tcc/text-emphasis-options identity)
        reported-id (:id (select/get-option options "filled-dot"))]
    (t/is (= "filled-dot" reported-id))
    (t/is (= "filled-dot"
             (:id (select/get-option options reported-id))))))

(t/deftest annotation-clearance-select-reports-canonical-values
  (let [options (tcc/annotation-clearance-options identity)]
    (t/is (= ["none" "auto"] (mapv :id options)))
    (t/is (= "auto" (:id (select/get-option options "auto"))))))

(t/deftest whole-text-tate-chu-yoko-and-selection-combine-are-apart
  (t/is (= ["none" "digits"] (mapv :value (tll/tate-chu-yoko-options identity))))
  (t/is (= ["none" "all"] (mapv :value (tll/combine-options identity)))))

(t/deftest ruby-container-styles-preserve-customization
  (let [style (text-styles/generate-ruby-container-styles
               {:ruby-align "space-between"
                :ruby-overhang "none"
                :ruby-side "under"})]
    (t/is (= "space-between" (.-rubyAlign style)))
    (t/is (= "none" (.-rubyOverhang style)))
    (t/is (= "under" (.-rubyPosition style)))))

(t/deftest advanced-ruby-options-of-a-selection-are-collapsed-by-default
  (with-redefs [hooks/use-portal-container (fn ([] nil) ([_] nil))]
    (let [markup (rds/renderToStaticMarkup
                  (mf/element tcc/ruby-advanced-options*
                              #js {:values {}
                                   :labels {:input "workspace.options.text-options.ruby"
                                            :placeholder "workspace.options.text-options.ruby-placeholder"
                                            :advanced "workspace.options.text-options.ruby-advanced-options"}
                                   :on-change identity
                                   :on-blur identity}))]
      (t/is (str/includes? markup "data-testid=\"ruby-advanced-options-toggle\""))
      (t/is (str/includes? markup "aria-expanded=\"false\"")))))

(t/deftest advanced-furigana-options-include-hide-toggle
  (let [markup (rds/renderToStaticMarkup
                (mf/element tcc/ruby-hidden-option*
                            #js {:values {:ruby-hidden true}
                                 :on-change identity
                                 :on-blur identity}))]
    (t/is (str/includes? markup "workspace.options.text-options.ruby-hidden"))
    (t/is (str/includes? markup "role=\"switch\""))
    (t/is (str/includes? markup "aria-checked=\"true\""))))

(t/deftest hidden-furigana-renders-base-text-without-ruby-layout
  (let [markup (rds/renderToStaticMarkup
                (mf/element html-text/render-text*
                            #js {:node {:text "日"
                                        :ruby "にち"
                                        :ruby-hidden true}
                                 :parent {}
                                 :shape {}}))]
    (t/is (str/includes? markup ">日</span>"))
    (t/is (not (str/includes? markup "<ruby")))))

(t/deftest shape-level-furigana-presentation-only-updates-ruby-spans
  (let [shape   {:content
                 {:type "root"
                  :children
                  [{:type "paragraph-set"
                    :children
                    [{:type "paragraph"
                      :children
                      [{:text "日"
                        :ruby "にち"
                        :ruby-hidden false
                        :ruby-size "half"}
                       {:text "本"
                        :ruby ""
                        :ruby-hidden false
                        :ruby-size "quarter"}]}]}]}}
        values  (dwt/current-ruby-values {:shape shape
                                          :attrs cjk/ruby-presentation-attrs})
        updated (dwt/update-ruby-presentation-attrs
                 shape {:ruby-hidden true :ruby-size "third"})
        spans   (get-in updated [:content :children 0 :children 0 :children])]
    (t/is (= "half" (:ruby-size values)))
    (t/is (false? (:ruby-hidden values)))
    (t/is (= "third" (:ruby-size (first spans))))
    (t/is (true? (:ruby-hidden (first spans))))
    (t/is (= "quarter" (:ruby-size (second spans))))
    (t/is (false? (:ruby-hidden (second spans))))))

(t/deftest mixed-span-values-select-a-disabled-mixed-option
  (let [options (tcc/with-mixed-span-option (tcc/text-emphasis-options identity) :multiple)]
    (t/is (= "mixed" (tcc/span-select-value :multiple "none")))
    (t/is (= "none" (tcc/span-select-value nil "none")))
    (t/is (= "mixed" (:id (last options))))
    (t/is (true? (:disabled (last options))))
    (t/is (= options (tcc/with-mixed-span-option options "none"))
          "an unmixed value adds no entry")))

(t/deftest mixed-radio-values-select-nothing
  (t/is (= "" (radio-selected :multiple "none")))
  (t/is (= "none" (radio-selected nil "none")))
  (t/is (= "vertical-rl" (radio-selected :vertical-rl))))

(t/deftest whole-text-selection-reports-every-differing-span-value-as-mixed
  (let [shape {:content
               {:type "root"
                :children
                [{:type "paragraph-set"
                  :children
                  [{:type "paragraph"
                    :children
                    [{:text "日"
                      :text-combine-upright "digits2"
                      :text-emphasis "filled-dot"
                      :ruby "にち"
                      :ruby-hidden true
                      :ruby-size "third"
                      :ruby-align "center"
                      :ruby-overhang "none"
                      :ruby-side "under"
                      :warichu "warichu"
                      :font-features "palt"
                      :annotation-clearance "auto"}
                     {:text "本"
                      :text-combine-upright "none"
                      :text-emphasis "none"
                      :ruby ""
                      :ruby-hidden false
                      :ruby-size "half"
                      :ruby-align "space-around"
                      :ruby-overhang "auto"
                      :ruby-side "over"
                      :warichu "none"
                      :font-features "none"
                      :annotation-clearance "none"}]}]}]}}
        values (dwt/current-text-values {:shape shape
                                         :attrs japanese-span-attrs})]
    (t/is (= (zipmap japanese-span-attrs (repeat :multiple)) values))))

(t/deftest wasm-editor-span-styles-preserve-persisted-vertical-paragraph-values
  (let [shape {:content
               {:type "root"
                :children
                [{:type "paragraph-set"
                  :children
                  [{:type "paragraph"
                    :writing-mode "vertical-rl"
                    :text-orientation "upright"
                    :children [{:text "12" :text-combine-upright "none"}]}]}]}}
        editor-styles {:text-combine-upright "digits2"}
        paragraph-values (dwt/current-paragraph-values
                          {:editor-styles editor-styles
                           :shape shape
                           :attrs [:writing-mode :text-orientation]})
        text-values (dwt/current-text-values
                     {:editor-styles editor-styles
                      :shape shape
                      :attrs [:text-combine-upright]})]
    (t/is (= "vertical-rl" (:writing-mode paragraph-values)))
    (t/is (= "upright" (:text-orientation paragraph-values)))
    (t/is (= "digits2" (:text-combine-upright text-values)))))

(t/deftest japanese-controls-distinguish-horizontal-and-vertical-modes
  (t/is (false? (tcc/vertical-writing? {})))
  (t/is (false? (tcc/vertical-writing?
                 {:writing-mode "horizontal-tb"})))
  (t/is (true? (tcc/vertical-writing?
                {:writing-mode "vertical-rl"})))
  (t/is (= "palt"
           (tll/proportional-metrics-feature "horizontal-tb")))
  (t/is (= "palt"
           (tll/proportional-metrics-feature nil)))
  (t/is (= "vpal"
           (tll/proportional-metrics-feature "vertical-rl"))))

(t/deftest sidebar-options-offer-the-japanese-enum-values
  (let [ids    #(mapv :id %)
        values #(mapv :value %)]
    (t/is (= (:writing-mode cjk/enum-values) (values (tcc/writing-mode-options identity))))
    (t/is (= (:text-orientation cjk/enum-values) (values (tcc/text-orientation-options identity))))
    (t/is (= (:warichu cjk/enum-values) (values (tll/warichu-options identity))))
    (t/is (= (:text-emphasis cjk/enum-values) (ids (tcc/text-emphasis-options identity))))
    (t/is (= (:annotation-clearance cjk/enum-values) (ids (tcc/annotation-clearance-options identity))))
    (t/is (= (:ruby-size cjk/enum-values) (ids (tcc/ruby-size-options identity))))
    (t/is (= (:ruby-align cjk/enum-values) (ids (tcc/ruby-align-options identity))))
    (t/is (= (:ruby-overhang cjk/enum-values) (ids (tcc/ruby-overhang-options identity))))
    (t/is (= (:ruby-side cjk/enum-values) (ids (tcc/position-options :ruby-side false))))
    (t/is (= (:ruby-side cjk/enum-values) (ids (tcc/position-options :ruby-side true))))
    (t/is (= (:line-adjustment cjk/enum-values) (ids (tll/line-adjustment-options identity))))
    (t/is (every? #(cjk/valid-enum-value? :text-combine-upright %)
                  (values (concat (tll/tate-chu-yoko-options identity)
                                  (tll/combine-options identity)))))
    (t/is (every? cjk/digit-combine? (ids (tll/text-combine-upright-count-options identity))))))

(t/deftest html-text-drops-vertical-writing-under-a-horizontal-renderer
  (let [shape  {:id (random-uuid) :width 100 :height 100
                :content {:type "root"
                          :children
                          [{:type "paragraph-set"
                            :children
                            [{:type "paragraph"
                              :text-language "ja"
                              :writing-mode "vertical-rl"
                              :text-orientation "upright"
                              :children [{:text "縦"}]}]}]}}
        markup #(rds/renderToStaticMarkup (mf/element html-text/text-shape* #js {:shape shape}))]
    (with-redefs [wm/vertical-layout-active? (constantly false)]
      (t/is (not (str/includes? (markup) "vertical-rl"))))
    (with-redefs [wm/vertical-layout-active? (constantly true)]
      (t/is (str/includes? (markup) "writing-mode:vertical-rl")))))

(t/deftest vertical-writing-only-renders-under-a-cjk-language
  (let [shape  {:id (random-uuid) :width 100 :height 100
                :content {:type "root"
                          :children
                          [{:type "paragraph-set"
                            :children
                            [{:type "paragraph"
                              :writing-mode "vertical-rl"
                              :children [{:text "縦"}]}]}]}}
        markup #(rds/renderToStaticMarkup (mf/element html-text/text-shape* #js {:shape shape}))]
    (with-redefs [wm/vertical-layout-active? (constantly true)]
      (t/is (not (str/includes? (markup) "vertical-rl"))
            "a text without language renders horizontal"))))

(t/deftest vertical-controls-need-a-cjk-language
  (with-redefs [wm/vertical-layout-active? (constantly true)]
    (t/is (true? (wm/vertical-values? {:writing-mode "vertical-rl" :text-language "ko"})))
    (t/is (true? (wm/vertical-values? {:writing-mode "vertical-rl" :text-language :multiple})))
    (t/is (false? (wm/vertical-values? {:writing-mode "vertical-rl"})))
    (t/is (false? (wm/vertical-values? {:writing-mode "vertical-rl" :text-language "en"})))
    (t/is (false? (wm/vertical-values? {:writing-mode "horizontal-tb" :text-language "ja"})))))

(defn- alignment-markup
  ([component writing-mode wasm-enabled]
   (alignment-markup component writing-mode wasm-enabled {}))
  ([component writing-mode wasm-enabled values]
   ;; Server rendering has no DOM container for tooltip portals.
   (with-redefs [wm/vertical-layout-active? (constantly wasm-enabled)
                 hooks/use-portal-container (fn ([] nil) ([_] nil))]
     (rds/renderToStaticMarkup
      (mf/element component #js {:values (merge {:text-language "ja"} values {:writing-mode writing-mode})
                                 :on-change identity})))))

(defn- alignment-labels
  [markup]
  (mapv second (re-seq #"aria-label=\"([^\"]+)\"" markup)))

(defn- alignment-icons
  [markup]
  (mapv second (re-seq #"href=\"#icon-([^\"]+)\"" markup)))

(t/deftest vertical-paragraph-alignment-shows-top-middle-bottom-and-vertical-justify
  (let [markup (alignment-markup text-menu/text-align-options* "vertical-rl" true)]
    (t/is (= ["text-align-top" "text-align-middle" "text-align-bottom" "text-justify-vertical"]
             (alignment-icons markup)))
    (t/is (= ["workspace.options.text-options.align-top"
              "workspace.options.text-options.align-middle"
              "workspace.options.text-options.align-bottom"
              "workspace.options.text-options.text-align-justify"]
             (alignment-labels markup)))
    (t/is (= ["left" "center" "right" "justify"]
             (mapv second (re-seq #"value=\"([^\"]+)\"" markup))))))

(t/deftest distribute-is-a-toggle-at-the-end-of-the-writing-mode-row
  (let [markup  #(alignment-markup tcc/writing-mode-row* "horizontal-tb" true {:text-align %})
        pressed #(second (re-find #"aria-pressed=\"([^\"]+)\"[^>]*data-testid=\"text-align-distribute\"" %))]
    (t/is (< (str/index-of (markup "left") "horizontal-tb-writing-mode")
             (str/index-of (markup "left") "data-testid=\"text-align-distribute\"")))
    (t/is (= "true" (pressed (markup "distribute"))))
    (t/is (= "false" (pressed (markup "left"))))
    (t/is (= "false" (pressed (markup :multiple))))))

(t/deftest writing-mode-row-shows-orientation-only-for-vertical-text
  (t/is (not (str/includes? (alignment-markup tcc/writing-mode-row* "horizontal-tb" true)
                            "mixed-text-orientation")))
  (t/is (str/includes? (alignment-markup tcc/writing-mode-row* "vertical-rl" true)
                       "mixed-text-orientation")))

(t/deftest writing-mode-row-needs-a-cjk-language
  (t/is (true? (tcc/cjk-values? {:text-language "ja"})))
  (t/is (true? (tcc/cjk-values? {:text-language :multiple})))
  (t/is (false? (tcc/cjk-values? {:text-language "en"})))
  (t/is (false? (tcc/cjk-values? {}))))

(t/deftest vertical-column-block-alignment-shows-right-center-and-left
  (let [markup (alignment-markup text-menu/vertical-align* "vertical-rl" true)]
    (t/is (= ["text-right" "text-horizontal-center" "text-left"]
             (alignment-icons markup)))
    (t/is (= ["workspace.options.text-options.text-align-right"
              "workspace.options.text-options.text-align-center"
              "workspace.options.text-options.text-align-left"]
             (alignment-labels markup)))
    (t/is (= ["top" "center" "bottom"]
             (mapv second (re-seq #"value=\"([^\"]+)\"" markup))))))

(t/deftest horizontal-and-mixed-writing-modes-use-horizontal-alignment-controls
  (doseq [writing-mode [nil "horizontal-tb" :multiple]]
    (let [paragraph (alignment-markup text-menu/text-align-options* writing-mode true)
          block     (alignment-markup text-menu/vertical-align* writing-mode true)]
      (t/is (= ["text-align-left" "text-align-center" "text-align-right" "text-justify"]
               (alignment-icons paragraph)))
      (t/is (= ["text-top" "text-middle" "text-bottom"] (alignment-icons block))))))

(t/deftest svg-renderer-keeps-horizontal-alignment-for-stored-vertical-text
  (let [paragraph (alignment-markup text-menu/text-align-options* "vertical-rl" false)
        block     (alignment-markup text-menu/vertical-align* "vertical-rl" false)]
    (t/is (= ["text-align-left" "text-align-center" "text-align-right" "text-justify"]
             (alignment-icons paragraph)))
    (t/is (= ["text-top" "text-middle" "text-bottom"] (alignment-icons block)))
    (t/is (= "workspace.options.text-options.text-align-left" (first (alignment-labels paragraph))))
    (t/is (= "workspace.options.text-options.align-top" (first (alignment-labels block))))))


(t/deftest vertical-text-growth-controls-show-the-physical-growth-direction
  (let [markup (alignment-markup text-menu/grow-options* "vertical-rl" true)]
    (t/is (= ["text-fixed" "text-auto-width-vertical" "text-auto-height-vertical"]
             (alignment-icons markup)))
    (t/is (= ["workspace.options.text-options.grow-fixed"
              "workspace.options.text-options.grow-auto-height"
              "workspace.options.text-options.grow-auto-width"]
             (alignment-labels markup)))
    (t/is (= ["fixed" "auto-width" "auto-height"]
             (mapv second (re-seq #"value=\"([^\"]+)\"" markup))))))

(t/deftest horizontal-and-svg-text-growth-controls-keep-horizontal-directions
  (doseq [[writing-mode wasm-enabled] [[nil true]
                                       ["horizontal-tb" true]
                                       [:multiple true]
                                       ["vertical-rl" false]]]
    (let [markup (alignment-markup text-menu/grow-options* writing-mode wasm-enabled)]
      (t/is (= ["text-fixed" "text-auto-width" "text-auto-height"]
               (alignment-icons markup)))
      (t/is (= ["workspace.options.text-options.grow-fixed"
                "workspace.options.text-options.grow-auto-width"
                "workspace.options.text-options.grow-auto-height"]
               (alignment-labels markup))))))


(t/deftest japanese-layout-controls-include-line-adjustment
  (let [markup (alignment-markup tll/japanese-layout-options* "vertical-rl" true)]
    (t/is (str/includes? markup "workspace.options.text-options.line-adjustment"))))


(t/deftest vertical-text-hides-ltr-and-rtl-controls
  (t/is (empty? (alignment-markup text-menu/text-direction-options* "vertical-rl" true))))

(t/deftest horizontal-and-svg-text-show-ltr-and-rtl-controls
  (doseq [[writing-mode wasm-enabled] [[nil true]
                                       ["horizontal-tb" true]
                                       [:multiple true]
                                       ["vertical-rl" false]]]
    (let [markup (alignment-markup text-menu/text-direction-options* writing-mode wasm-enabled)]
      (t/is (= ["text-ltr" "text-rtl"] (alignment-icons markup))))))

(t/deftest hiding-direction-controls-preserves-the-stored-direction
  (let [values {:text-direction "rtl"}
        render #(alignment-markup text-menu/text-direction-options* % true values)]
    (t/is (str/includes? (re-find #"<input[^>]*id=\"rtl-text-direction\"[^>]*>"
                                  (render "horizontal-tb"))
                         "checked=\"\""))
    (t/is (empty? (render "vertical-rl")))
    (t/is (str/includes? (re-find #"<input[^>]*id=\"rtl-text-direction\"[^>]*>"
                                  (render "horizontal-tb"))
                         "checked=\"\""))))


(t/deftest decoration-position-changes-refresh-the-text-options
  (doseq [attr [:text-emphasis-position :text-underline-position]]
    (let [before (mf/props {:values {attr "auto"}})
          after (mf/props {:values {attr "under-left"}})]
      (t/is (false? (@#'text-menu/check-props before after))))))

(t/deftest zhuyin-handoff-sets-symbols-upright-in-a-right-side-strip
  (let [html (rds/renderToStaticMarkup
              (mf/element html-text/render-text*
                          {:node {:text "中" :ruby "ㄋㄧˇ" :ruby-zhuyin true :font-size "40"}
                           :shape {} :parent {}}))]
    (t/is (str/includes? html "width:1.6em"))
    (t/is (str/includes? html "writing-mode:horizontal-tb"))
    (t/is (str/includes? html "ㄋ"))
    (t/is (str/includes? html "ㄧ"))
    (t/is (str/includes? html "ˇ"))
    (t/is (not (str/includes? html "<rt")))))

(t/deftest positions-show-only-the-sides-of-the-writing-mode
  (let [labels #(mapv :label (tcc/position-options %1 %2))]
    (t/is (= ["workspace.options.text-options.position-over"
              "workspace.options.text-options.position-under"]
             (labels :ruby-side false)))
    (t/is (= ["workspace.options.text-options.position-right"
              "workspace.options.text-options.position-left"]
             (labels :ruby-side true)))
    (t/is (= ["auto" "over" "under"] (mapv :id (tcc/position-options :text-emphasis-position false))))
    (t/is (= ["auto" "right" "left"] (mapv :id (tcc/position-options :text-emphasis-position true))))
    (t/is (= ["auto" "under"] (mapv :id (tcc/position-options :text-underline-position false))))
    (t/is (= ["auto" "right" "left"] (mapv :id (tcc/position-options :text-underline-position true))))))

(t/deftest picking-a-position-keeps-the-side-of-the-other-writing-mode
  (t/is (= "under" (tcc/position-choice :text-emphasis-position false "under-left")))
  (t/is (= "left" (tcc/position-choice :text-emphasis-position true "under-left")))
  (t/is (= "auto" (tcc/position-choice :text-emphasis-position true "auto")))
  (t/is (= "under-left" (tcc/position-value :text-emphasis-position false "over-left" "under")))
  (t/is (= "under-right" (tcc/position-value :text-emphasis-position true "under-left" "right")))
  (t/is (= "over-right" (tcc/position-value :text-emphasis-position false "auto" "over")))
  (t/is (= "auto" (tcc/position-value :text-emphasis-position false "under-left" "auto")))
  (t/is (= "under" (tcc/position-choice :text-underline-position false "under-right")))
  (t/is (= "auto" (tcc/position-choice :text-underline-position false "left")))
  (t/is (= "right" (tcc/position-choice :text-underline-position true "under-right")))
  (t/is (= "under-left" (tcc/position-value :text-underline-position false "left" "under")))
  (t/is (= "left" (tcc/position-value :text-underline-position false "under-left" "auto")))
  (t/is (= "under-right" (tcc/position-value :text-underline-position true "under" "right")))
  (t/is (= "under" (tcc/position-value :text-underline-position true "under-left" "auto")))
  (t/is (= "under" (tcc/position-value :ruby-side true "over" "under")))
  (t/is (= :multiple (tcc/position-choice :text-emphasis-position false :multiple))))
