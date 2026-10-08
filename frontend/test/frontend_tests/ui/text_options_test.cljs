;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.text-options-test
  (:require
   ["react-dom/server" :as rds]
   [app.common.types.text.japanese-layout :as jl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.ui.ds.controls.select :as select]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.shapes.text.html-text :as html-text]
   [app.main.ui.shapes.text.styles :as text-styles]
   [app.main.ui.workspace.sidebar.options.common :refer [radio-selected]]
   [app.main.ui.workspace.sidebar.options.menus.text :as text-menu]
   [app.main.ui.workspace.sidebar.options.menus.text-japanese-layout :as tjl]
   [app.util.text.writing-mode :as wm]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
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


(t/deftest japanese-layout-controls-follow-file-or-profile-configuration
  (with-redefs [wm/vertical-layout-active? (constantly true)]
    (t/is (false? (tjl/japanese-layout-config-enabled? {})))
    (t/is (false? (tjl/japanese-layout-config-enabled? {:file? false :all-files? false})))
    (t/is (true? (tjl/japanese-layout-config-enabled? {:file? true :all-files? false})))
    (t/is (true? (tjl/japanese-layout-config-enabled? {:file? false :all-files? true}))))
  (with-redefs [wm/vertical-layout-active? (constantly false)]
    (t/is (false? (tjl/japanese-layout-config-enabled? {:file? true :all-files? true})))))

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

(t/deftest advanced-furigana-options-include-hide-toggle
  (let [markup (rds/renderToStaticMarkup
                (mf/element tjl/ruby-hidden-option*
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
                                          :attrs jl/ruby-presentation-attrs})
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
  (let [options (tjl/with-mixed-span-option (tjl/text-emphasis-options identity) :multiple)]
    (t/is (= "mixed" (tjl/span-select-value :multiple "none")))
    (t/is (= "none" (tjl/span-select-value nil "none")))
    (t/is (= "mixed" (:id (last options))))
    (t/is (true? (:disabled (last options))))
    (t/is (= options (tjl/with-mixed-span-option options "none"))
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
  (t/is (false? (tjl/vertical-japanese-layout? {})))
  (t/is (false? (tjl/vertical-japanese-layout?
                 {:writing-mode "horizontal-tb"})))
  (t/is (true? (tjl/vertical-japanese-layout?
                {:writing-mode "vertical-rl"})))
  (t/is (= "palt"
           (tjl/proportional-metrics-feature "horizontal-tb")))
  (t/is (= "palt"
           (tjl/proportional-metrics-feature nil)))
  (t/is (= "vpal"
           (tjl/proportional-metrics-feature "vertical-rl"))))

(t/deftest sidebar-options-offer-the-japanese-enum-values
  (let [ids    #(mapv :id %)
        values #(mapv :value %)]
    (t/is (= (:writing-mode jl/enum-values) (values (tjl/writing-mode-options identity))))
    (t/is (= (:text-orientation jl/enum-values) (values (tjl/text-orientation-options identity))))
    (t/is (= (:warichu jl/enum-values) (values (tjl/warichu-options identity))))
    (t/is (= (:text-emphasis jl/enum-values) (ids (tjl/text-emphasis-options identity))))
    (t/is (= (:annotation-clearance jl/enum-values) (ids (tjl/annotation-clearance-options identity))))
    (t/is (= (:ruby-size jl/enum-values) (ids (tjl/ruby-size-options identity))))
    (t/is (= (:ruby-align jl/enum-values) (ids (tjl/ruby-align-options identity))))
    (t/is (= (:ruby-overhang jl/enum-values) (ids (tjl/ruby-overhang-options identity))))
    (t/is (= (:ruby-side jl/enum-values) (ids (tjl/ruby-side-options identity))))
    (t/is (= (:line-adjustment jl/enum-values) (ids (tjl/line-adjustment-options identity))))
    (t/is (every? #(jl/valid-enum-value? :text-combine-upright %)
                  (values (tjl/text-combine-upright-options true identity))))
    (t/is (every? jl/digit-combine? (ids (tjl/text-combine-upright-count-options identity))))))

(t/deftest html-text-drops-vertical-writing-under-a-horizontal-renderer
  (let [shape  {:id (random-uuid) :width 100 :height 100
                :content {:type "root"
                          :children
                          [{:type "paragraph-set"
                            :children
                            [{:type "paragraph"
                              :writing-mode "vertical-rl"
                              :text-orientation "upright"
                              :children [{:text "縦"}]}]}]}}
        markup #(rds/renderToStaticMarkup (mf/element html-text/text-shape* #js {:shape shape}))]
    (with-redefs [wm/vertical-layout-active? (constantly false)]
      (t/is (not (str/includes? (markup) "vertical-rl"))))
    (with-redefs [wm/vertical-layout-active? (constantly true)]
      (t/is (str/includes? (markup) "writing-mode:vertical-rl")))))

(defn- alignment-markup
  ([component writing-mode wasm-enabled]
   (alignment-markup component writing-mode wasm-enabled {}))
  ([component writing-mode wasm-enabled values]
   ;; Server rendering has no DOM container for tooltip portals.
   (with-redefs [wm/vertical-layout-active? (constantly wasm-enabled)
                 hooks/use-portal-container (fn ([] nil) ([_] nil))]
     (rds/renderToStaticMarkup
      (mf/element component #js {:values (assoc values :writing-mode writing-mode)
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
  (let [markup (alignment-markup tjl/japanese-layout-options* "vertical-rl" true)]
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
