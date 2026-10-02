;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.texts-test
  "Japanese text support in the WASM bridge: span serialization, editor
   selection styles, IME composition caret placement, and the CJK script
   classification used to pick Noto fallback fonts."
  (:require
   [app.common.fonts :as cfnt]
   [app.common.render-wasm.text-content :as tc]
   [app.main.ui.workspace.shapes.text.v3-editor :as v3-editor]
   [app.render-wasm.api :as api]
   [app.render-wasm.api.texts :as texts]
   [app.render-wasm.text-editor :as text-editor]
   [cljs.test :as t :include-macros true]))

(defn- write-spans
  [offset dview spans paragraph]
  (@#'tc/write-spans offset dview spans paragraph cfnt/font-id->uuid))

(defn- langs [text]
  (cfnt/collect-used-languages #{} text))

(defn- font-ids
  [fonts]
  (into #{} (map :font-id) fonts))

(defn- editor-content
  [& paragraphs]
  {:type "root"
   :children [{:type "paragraph-set"
               :children (mapv (fn [spans]
                                 {:type "paragraph"
                                  :children spans})
                               paragraphs)}]})

(defn- selection
  [anchor-para anchor-offset focus-para focus-offset]
  {:anchor-para anchor-para
   :anchor-offset anchor-offset
   :focus-para focus-para
   :focus-offset focus-offset})

(t/deftest japanese-styles-follow-the-selected-span
  (let [content (editor-content
                 [{:text "日" :ruby "にち" :text-emphasis "filled-dot"}
                  {:text "本"}])]
    (t/is (= {:text-combine-upright "none"
              :text-emphasis "filled-dot"
              :ruby "にち"
              :ruby-hidden false
              :ruby-size "half"
              :ruby-align "space-around"
              :ruby-overhang "auto"
              :ruby-side "over"
              :warichu "none"
              :font-features "none"
              :annotation-clearance "none"}
             (text-editor/selection-japanese-styles
              content (selection 0 0 0 1))))
    (t/is (= {:text-combine-upright "none"
              :text-emphasis "none"
              :ruby ""
              :ruby-hidden false
              :ruby-size "half"
              :ruby-align "space-around"
              :ruby-overhang "auto"
              :ruby-side "over"
              :warichu "none"
              :font-features "none"
              :annotation-clearance "none"}
             (text-editor/selection-japanese-styles
              content (selection 0 1 0 2))))))

(t/deftest japanese-styles-report-mixed-ranges-and-follow-the-caret
  (let [content (editor-content
                 [{:text "平成"
                   :text-combine-upright "digits2"
                   :text-emphasis "filled-dot"
                   :ruby "へいせい"
                   :ruby-hidden true
                   :ruby-size "third"
                   :ruby-align "center"
                   :ruby-overhang "none"
                   :ruby-side "under"
                   :annotation-clearance "auto"}
                  {:text "年"
                   :ruby-hidden false
                   :warichu "warichu"
                   :font-features "vpal"}])]
    (t/is (= {:text-combine-upright :multiple
              :text-emphasis :multiple
              :ruby :multiple
              :ruby-hidden :multiple
              :ruby-size :multiple
              :ruby-align :multiple
              :ruby-overhang :multiple
              :ruby-side :multiple
              :warichu :multiple
              :font-features :multiple
              :annotation-clearance :multiple}
             (text-editor/selection-japanese-styles
              content (selection 0 0 0 3))))
    ;; A caret at the boundary belongs to the preceding span, as in WASM.
    (t/is (= "digits2"
             (:text-combine-upright
              (text-editor/selection-japanese-styles
               content (selection 0 2 0 2)))))
    (t/is (= "warichu"
             (:warichu
              (text-editor/selection-japanese-styles
               content (selection 0 3 0 3)))))))

(t/deftest japanese-selection-offsets-are-utf16-code-units
  (let [content (editor-content
                 [{:text "😀"}
                  {:text "日" :ruby "にち"}])]
    (t/is (= "にち"
             (:ruby
              (text-editor/selection-japanese-styles
               content (selection 0 2 0 3)))))))

(t/deftest composition-caret-goes-to-the-end-of-the-changed-part
  ;; Typing appends, converting replaces all, and a candidate for a middle
  ;; clause changes only that clause.
  (t/is (= 1 (v3-editor/changed-span-end "" "に")))
  (t/is (= 2 (v3-editor/changed-span-end "に" "にほ")))
  (t/is (= 3 (v3-editor/changed-span-end "にほんご" "日本語")))
  (t/is (= 5 (v3-editor/changed-span-end "日本語を書く" "日本語を掻く")))
  (t/is (= 3 (v3-editor/changed-span-end "ああ" "あああ")))
  (t/is (= 0 (v3-editor/changed-span-end "にほ" "")))
  (t/is (= 2 (v3-editor/changed-span-end "😀" "😀😀"))))

(t/deftest whole-shape-paragraph-attrs-apply-to-every-paragraph
  (let [content (-> (editor-content [{:text "一"}] [{:text "二"}] [{:text "三"}])
                    (assoc-in [:children 0 :children 2 :text-orientation] "upright"))
        result  (text-editor/apply-paragraph-attrs-to-range
                 content
                 (selection 1 0 1 0)
                 {:writing-mode "vertical-rl"
                  :text-orientation nil
                  :text-align "center"})
        paragraphs (-> result :children first :children)]
    (t/is (every? #(= "vertical-rl" (:writing-mode %)) paragraphs))
    (t/is (not-any? #(contains? % :text-orientation) paragraphs))
    (t/is (= [nil "center" nil] (mapv :text-align paragraphs)))))

;; Byte offsets inside one span's attribute block (see `write-spans` and
;; RawTextSpan in render-wasm).
(def ^:private span-attr-offset
  {:text-combine-upright 5
   :font-features        8
   :annotation-clearance 9
   :ruby-size            10
   :ruby-align           11
   :ruby-overhang        12
   :ruby-side            13
   :padding              [14 15]
   :ruby-length          72})

(def ^:private span-paragraph
  {:font-size "16" :font-weight "400" :line-height "1"})

(defn- serialized-span
  "DataView over the attribute block `write-spans` produces for `span`."
  [span]
  (let [dview (js/DataView. (js/ArrayBuffer. 256))]
    (write-spans 0 dview [(merge {:font-size "16" :font-weight "400"} span)] span-paragraph)
    dview))

(defn- span-byte
  [dview attr]
  (.getUint8 dview (get span-attr-offset attr)))

(defn- padding-bytes
  [dview]
  (mapv #(.getUint8 dview %) (:padding span-attr-offset)))

(t/deftest write-spans-serializes-font-features
  (let [dview (serialized-span {:text "日本語" :font-features "vpal"})]
    (t/is (= 2 (span-byte dview :font-features)))
    (t/is (= 0 (span-byte dview :annotation-clearance)))
    (t/is (= [0 0] (padding-bytes dview)))))

(t/deftest write-spans-serializes-annotation-clearance
  (let [dview (serialized-span {:text "漢字" :annotation-clearance "auto"})]
    (t/is (= 1 (span-byte dview :annotation-clearance)))
    (t/is (= [0 0] (padding-bytes dview)))))

(t/deftest write-spans-serializes-ruby-customization
  (let [dview (serialized-span {:text "漢字"
                                :ruby-size "quarter"
                                :ruby-align "space-between"
                                :ruby-overhang "none"
                                :ruby-side "under"})]
    (t/is (= [2 3 1 1]
             (mapv #(span-byte dview %) [:ruby-size :ruby-align :ruby-overhang :ruby-side])))
    (t/is (= [0 0] (padding-bytes dview)))))

(t/deftest write-spans-omits-hidden-ruby
  (let [ruby-length #(.getInt32 % (:ruby-length span-attr-offset) true)]
    (t/is (= 0 (ruby-length (serialized-span {:text "日" :ruby "にち" :ruby-hidden true}))))
    (t/is (pos? (ruby-length (serialized-span {:text "日" :ruby "にち" :ruby-hidden false}))))))

(t/deftest write-spans-serializes-counted-digits-tcy
  (let [dview (serialized-span {:text "平成31年" :text-combine-upright "digits2"})]
    (t/is (= 3 (span-byte dview :text-combine-upright)))))

(t/deftest ruby-text-participates-in-live-and-reload-fallback-discovery
  (let [content {:children
                 [{:children
                   [{:children
                     [{:text "Penpot"
                       :ruby "ぺんぽっと😀"}]}]}]}
        expected #{"gfont-noto-sans-jp" "gfont-noto-color-emoji"}]
    (with-redefs [texts/write-shape-text (fn [& _])]
      (t/is (every? (font-ids (api/fonts-from-text-content content true)) expected)))
    (t/is (every? (font-ids (api/fonts-from-text-content content false)) expected))))

(t/deftest classification-kana
  ;; Hiragana/katakana are unambiguously Japanese.
  (t/is (= #{:japanese} (langs "ひらがなとカタカナ")))
  ;; Half-width katakana too.
  (t/is (= #{:japanese} (langs "ﾃﾞｻﾞｲﾝ"))))

(t/deftest classification-han-is-ambiguous
  ;; Kanji-only text is ambiguous Han, not a concrete language.
  (t/is (= #{:han} (langs "東京都渋谷区神南一丁目"))))

(t/deftest classification-cjk-punctuation
  ;; CJK punctuation and full-width forms match the shared class.
  (t/is (= #{:cjk-punctuation} (langs "、。「」『』（）")))
  (t/is (= #{:cjk-punctuation} (langs "！？：；１２３ＡＢＣ"))))

(t/deftest classification-mixed-japanese
  (t/is (= #{:japanese :han :cjk-punctuation}
           (langs "「こんにちは」と彼は言った。"))))

(t/deftest classification-korean
  (t/is (= #{:korean} (langs "안녕하세요"))))

(t/deftest resolve-kana-wins-over-locale
  ;; Kana in the same content implies Japanese regardless of locale.
  (t/is (= #{:japanese}
           (cfnt/resolve-ambiguous-cjk #{:japanese :han :cjk-punctuation} "zh")))
  (t/is (= #{:japanese}
           (cfnt/resolve-ambiguous-cjk #{:japanese :han} "en"))))

(t/deftest resolve-hangul-wins-over-locale
  (t/is (= #{:korean}
           (cfnt/resolve-ambiguous-cjk #{:korean :han} "ja"))))

(t/deftest resolve-han-only-uses-locale
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} "ja")))
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} "ja_paid")))
  (t/is (= #{:korean}   (cfnt/resolve-ambiguous-cjk #{:han} "ko")))
  (t/is (= #{:chinese}  (cfnt/resolve-ambiguous-cjk #{:han} "zh_cn"))))

(t/deftest resolve-han-only-defaults-to-chinese
  ;; Without kana, hangul or a CJK locale, Han resolves to Chinese (Noto Sans SC).
  (t/is (= #{:chinese} (cfnt/resolve-ambiguous-cjk #{:han} "en")))
  (t/is (= #{:chinese} (cfnt/resolve-ambiguous-cjk #{:han} nil))))

(t/deftest resolve-punctuation-only
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:cjk-punctuation} "ja")))
  (t/is (= #{:chinese}  (cfnt/resolve-ambiguous-cjk #{:cjk-punctuation} "en"))))

(t/deftest resolve-leaves-unambiguous-sets-alone
  (t/is (= #{:latin-ext} (cfnt/resolve-ambiguous-cjk #{:latin-ext} "ja")))
  (t/is (= #{} (cfnt/resolve-ambiguous-cjk #{} "ja")))
  ;; Other detected languages survive resolution.
  (t/is (= #{:japanese :cyrillic}
           (cfnt/resolve-ambiguous-cjk #{:han :cyrillic} "ja"))))

(t/deftest resolve-han-unification
  ;; A kanji-only Japanese address resolves to Japanese under a ja locale.
  (t/is (= #{:japanese}
           (cfnt/resolve-ambiguous-cjk (langs "東京都渋谷区神南一丁目") "ja"))))
