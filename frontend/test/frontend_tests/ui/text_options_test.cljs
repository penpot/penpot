;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.text-options-test
  (:require
   ["react-dom/server" :as rds]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.ds.controls.select :as select]
   [app.main.ui.shapes.text.styles :as text-styles]
   [app.main.ui.workspace.sidebar.options.menus.text-japanese-layout :as tjl]
   [app.util.text.content.styles :as content-styles]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private japanese-span-attrs
  [:text-combine-upright
   :text-emphasis
   :ruby
   :ruby-size
   :ruby-align
   :ruby-overhang
   :ruby-side
   :warichu
   :font-features
   :annotation-clearance])

(def ^:private radio-selected @#'tjl/radio-selected)
(def ^:private span-input-value @#'tjl/span-input-value)
(def ^:private span-select-value @#'tjl/span-select-value)
(def ^:private with-mixed-span-option @#'tjl/with-mixed-span-option)
(def ^:private ruby-common-props @#'tjl/ruby-common-props)

(t/deftest text-emphasis-select-reports-and-restores-canonical-values
  (let [options     (tjl/text-emphasis-options identity)
        reported-id (:id (select/get-option options "filled-dot"))]
    (t/is (= "filled-dot" reported-id))
    (t/is (= "filled-dot"
             (:id (select/get-option options reported-id))))))

(t/deftest annotation-clearance-select-reports-canonical-values
  (let [options (tjl/annotation-clearance-options identity)]
    (t/is (= ["none" "auto"] (mapv :id options)))
    (t/is (= "auto" (:id (select/get-option options "auto"))))))

(t/deftest unrestricted-tcy-is-only-offered-for-a-text-selection
  (t/is (= ["none" "digits"]
           (mapv :value (tjl/text-combine-upright-options false identity))))
  (t/is (= ["none" "all" "digits"]
           (mapv :value (tjl/text-combine-upright-options true identity)))))

(t/deftest ruby-container-styles-preserve-customization
  (let [style (text-styles/generate-ruby-container-styles
               {:ruby-align "space-between"
                :ruby-overhang "none"
                :ruby-side "under"})]
    (t/is (= "space-between" (.-rubyAlign style)))
    (t/is (= "none" (.-rubyOverhang style)))
    (t/is (= "under" (.-rubyPosition style)))))

(t/deftest advanced-furigana-options-are-collapsed-by-default
  (let [markup (rds/renderToStaticMarkup
                (mf/element tjl/ruby-presentation-options*
                            #js {:values {}
                                 :on-change identity
                                 :on-blur identity}))]
    (t/is (str/includes? markup "data-testid=\"ruby-presentation-options-toggle\""))
    (t/is (str/includes? markup "aria-expanded=\"false\""))
    (t/is (str/includes? markup "workspace.options.text-options.ruby-advanced-options"))))

(t/deftest advanced-furigana-options-preserve-change-callbacks
  (let [on-change identity
        on-blur   identity
        props     (ruby-common-props {} on-change on-blur)]
    (t/is (identical? on-change (unchecked-get props "onChange")))
    (t/is (identical? on-blur (unchecked-get props "onBlur")))))

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
                        :ruby-size "half"}
                       {:text "本"
                        :ruby ""
                        :ruby-size "quarter"}]}]}]}}
        values  (dwt/current-ruby-values {:shape shape
                                          :attrs dwt/ruby-presentation-attrs})
        updated (dwt/update-ruby-presentation-attrs shape {:ruby-size "third"})
        spans   (get-in updated [:content :children 0 :children 0 :children])]
    (t/is (= "half" (:ruby-size values)))
    (t/is (= "third" (:ruby-size (first spans))))
    (t/is (= "quarter" (:ruby-size (second spans))))))

(t/deftest mixed-japanese-span-values-have-distinct-control-states
  (let [options (with-mixed-span-option
                  (tjl/text-emphasis-options identity)
                  :multiple)]
    (t/is (= "mixed" (span-select-value :multiple "none")))
    (t/is (= "mixed" (span-input-value :multiple)))
    (t/is (= "mixed" (:id (last options))))
    (t/is (true? (:disabled (last options))))
    (t/is (= "" (radio-selected :multiple "none")))))

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

(t/deftest counted-tcy-values-round-trip-through-valid-css
  (t/is (= "digits 2"
           (unchecked-get
            (content-styles/attrs->styles {:text-combine-upright "digits2"})
            "text-combine-upright")))
  (t/is (= {:text-combine-upright "digits3"}
           (content-styles/styles->attrs
            {"text-combine-upright" "digits 3"}))))

(t/deftest japanese-layout-is-explicitly-enabled-by-writing-mode
  (t/is (false? (tjl/japanese-layout-enabled? {})))
  (t/is (false? (tjl/japanese-layout-enabled? {:writing-mode nil})))
  (t/is (true? (tjl/japanese-layout-enabled? {:writing-mode "horizontal-tb"})))
  (t/is (true? (tjl/japanese-layout-enabled? {:writing-mode "vertical-rl"})))
  (t/is (nil? (tjl/japanese-layout-enabled? {:writing-mode :multiple}))))

(t/deftest japanese-layout-toggle-emits-a-persisted-writing-mode
  (t/is (= {:writing-mode "horizontal-tb"}
           (tjl/japanese-layout-toggle-attrs true)))
  (t/is (= {:writing-mode nil}
           (tjl/japanese-layout-toggle-attrs false))))

(t/deftest japanese-layout-state-survives-transient-selection-styles
  (t/is (true? (tjl/reconcile-japanese-layout-state true {} false)))
  (t/is (true? (tjl/reconcile-japanese-layout-state false
                                                    {:writing-mode "vertical-rl"}
                                                    false)))
  (t/is (false? (tjl/reconcile-japanese-layout-state true {} true))))

(t/deftest japanese-controls-distinguish-horizontal-and-vertical-modes
  (t/is (false? (tjl/vertical-japanese-layout?
                 {:writing-mode "horizontal-tb"})))
  (t/is (true? (tjl/vertical-japanese-layout?
                {:writing-mode "vertical-rl"})))
  (t/is (= "palt"
           (tjl/proportional-metrics-feature "horizontal-tb")))
  (t/is (= "vpal"
           (tjl/proportional-metrics-feature "vertical-rl"))))
