;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.texts-test
  "Unit tests for CJK script classification and the Han-unification
   resolution policy used to pick Noto fallback fonts."
  (:require
   [app.render-wasm.api :as api]
   [app.render-wasm.api.texts :as texts]
   [app.render-wasm.text-editor :as text-editor]
   [app.render-wasm.wasm :as wasm]
   [cljs.test :as t :include-macros true]))

(def ^:private write-spans @#'texts/write-spans)

(defn- langs [text]
  (texts/collect-used-languages #{} text))

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
                   :ruby-size "third"
                   :ruby-align "center"
                   :ruby-overhang "none"
                   :ruby-side "under"
                   :annotation-clearance "auto"}
                  {:text "年"
                   :warichu "warichu"
                   :font-features "vpal"}])]
    (t/is (= {:text-combine-upright :multiple
              :text-emphasis :multiple
              :ruby :multiple
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

(t/deftest japanese-selection-offsets-count-non-bmp-text-as-one-character
  (let [content (editor-content
                 [{:text "😀"}
                  {:text "日" :ruby "にち"}])]
    (t/is (= "にち"
             (:ruby
              (text-editor/selection-japanese-styles
               content (selection 0 1 0 2)))))))

(t/deftest write-spans-serializes-font-features-in-reserved-byte
  (let [sentinel (js-obj)
        previous (if (.hasOwnProperty wasm/serializers "font-features")
                   (unchecked-get wasm/serializers "font-features")
                   sentinel)]
    (try
      (aset wasm/serializers "font-features" #js {"none" 0 "palt" 1 "vpal" 2})
      (let [buffer (js/ArrayBuffer. 256)
            dview  (js/DataView. buffer)
            span   {:text "日本語"
                    :font-size "16"
                    :font-weight "400"
                    :font-features "vpal"}
            paragraph {:font-size "16"
                       :font-weight "400"
                       :line-height "1"}]
        (write-spans 0 dview [span] paragraph)
        (t/is (= 2 (.getUint8 dview 8)))
        (t/is (= 0 (.getUint8 dview 9)))
        (t/is (= 0 (.getUint8 dview 14)))
        (t/is (= 0 (.getUint8 dview 15))))
      (finally
        (if (identical? sentinel previous)
          (js-delete wasm/serializers "font-features")
          (aset wasm/serializers "font-features" previous))))))

(t/deftest write-spans-serializes-annotation-clearance-in-reserved-byte
  (let [sentinel (js-obj)
        previous (if (.hasOwnProperty wasm/serializers "annotation-clearance")
                   (unchecked-get wasm/serializers "annotation-clearance")
                   sentinel)]
    (try
      (aset wasm/serializers "annotation-clearance" #js {"none" 0 "auto" 1})
      (let [buffer (js/ArrayBuffer. 256)
            dview  (js/DataView. buffer)
            span   {:text "漢字"
                    :font-size "16"
                    :font-weight "400"
                    :annotation-clearance "auto"}
            paragraph {:font-size "16"
                       :font-weight "400"
                       :line-height "1"}]
        (write-spans 0 dview [span] paragraph)
        (t/is (= 1 (.getUint8 dview 9)))
        (t/is (= 0 (.getUint8 dview 14)))
        (t/is (= 0 (.getUint8 dview 15))))
      (finally
        (if (identical? sentinel previous)
          (js-delete wasm/serializers "annotation-clearance")
          (aset wasm/serializers "annotation-clearance" previous))))))

(t/deftest write-spans-serializes-ruby-customization-bytes
  (let [buffer (js/ArrayBuffer. 256)
        dview  (js/DataView. buffer)
        span   {:text "漢字"
                :font-size "16"
                :font-weight "400"
                :ruby-size "quarter"
                :ruby-align "space-between"
                :ruby-overhang "none"
                :ruby-side "under"}
        paragraph {:font-size "16"
                   :font-weight "400"
                   :line-height "1"}]
    (write-spans 0 dview [span] paragraph)
    (t/is (= [2 3 1 1 0 0]
             (mapv #(.getUint8 dview %) (range 10 16))))))

(t/deftest write-spans-serializes-counted-digits-tcy
  (let [sentinel (js-obj)
        previous (if (.hasOwnProperty wasm/serializers "text-combine-upright")
                   (unchecked-get wasm/serializers "text-combine-upright")
                   sentinel)]
    (try
      (aset wasm/serializers "text-combine-upright"
            #js {"none" 0 "all" 1 "digits" 2 "digits2" 3 "digits3" 4})
      (let [buffer (js/ArrayBuffer. 256)
            dview  (js/DataView. buffer)
            span   {:text "平成31年"
                    :font-size "16"
                    :font-weight "400"
                    :text-combine-upright "digits2"}
            paragraph {:font-size "16"
                       :font-weight "400"
                       :line-height "1"}]
        (write-spans 0 dview [span] paragraph)
        ;; Byte 5 of the span attr block carries text-combine-upright.
        (t/is (= 3 (.getUint8 dview 5))))
      (finally
        (if (identical? sentinel previous)
          (js-delete wasm/serializers "text-combine-upright")
          (aset wasm/serializers "text-combine-upright" previous))))))

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
  ;; Kanji-only text (han-unification fixture) must NOT classify as a
  ;; concrete language; it is ambiguous Han.
  (t/is (= #{:han} (langs "東京都渋谷区神南一丁目"))))

(t/deftest classification-cjk-punctuation
  ;; CJK punctuation and full-width forms match the shared class
  ;; (previously they matched no range at all).
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
           (texts/resolve-ambiguous-cjk #{:japanese :han :cjk-punctuation} "zh")))
  (t/is (= #{:japanese}
           (texts/resolve-ambiguous-cjk #{:japanese :han} "en"))))

(t/deftest resolve-hangul-wins-over-locale
  (t/is (= #{:korean}
           (texts/resolve-ambiguous-cjk #{:korean :han} "ja"))))

(t/deftest resolve-han-only-uses-locale
  (t/is (= #{:japanese} (texts/resolve-ambiguous-cjk #{:han} "ja")))
  (t/is (= #{:japanese} (texts/resolve-ambiguous-cjk #{:han} "ja_paid")))
  (t/is (= #{:korean}   (texts/resolve-ambiguous-cjk #{:han} "ko")))
  (t/is (= #{:chinese}  (texts/resolve-ambiguous-cjk #{:han} "zh_cn"))))

(t/deftest resolve-han-only-defaults-to-chinese
  ;; Without kana/hangul and without a CJK locale, keep the previous
  ;; behavior (Noto Sans SC).
  (t/is (= #{:chinese} (texts/resolve-ambiguous-cjk #{:han} "en")))
  (t/is (= #{:chinese} (texts/resolve-ambiguous-cjk #{:han} nil))))

(t/deftest resolve-punctuation-only
  (t/is (= #{:japanese} (texts/resolve-ambiguous-cjk #{:cjk-punctuation} "ja")))
  (t/is (= #{:chinese}  (texts/resolve-ambiguous-cjk #{:cjk-punctuation} "en"))))

(t/deftest resolve-leaves-unambiguous-sets-alone
  (t/is (= #{:latin-ext} (texts/resolve-ambiguous-cjk #{:latin-ext} "ja")))
  (t/is (= #{} (texts/resolve-ambiguous-cjk #{} "ja")))
  ;; Other detected languages survive resolution.
  (t/is (= #{:japanese :cyrillic}
           (texts/resolve-ambiguous-cjk #{:han :cyrillic} "ja"))))

(t/deftest resolve-han-unification
  ;; A kanji-only Japanese address resolves to Japanese under a ja locale.
  (t/is (= #{:japanese}
           (texts/resolve-ambiguous-cjk (langs "東京都渋谷区神南一丁目") "ja"))))
