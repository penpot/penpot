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
   [app.common.render-wasm.serializers :as sr]
   [app.common.render-wasm.text-content :as tc]
   [app.common.render-wasm.wasm :as wasm]
   [app.common.types.text.cjk-layout :as cjk]
   [app.config :as cfg]
   [app.main.ui.workspace.shapes.text.ime-debug :as ime-debug]
   [app.main.ui.workspace.shapes.text.v3-editor :as v3-editor]
   [app.render-wasm.api :as api]
   [app.render-wasm.api.texts :as texts]
   [app.render-wasm.text-editor :as text-editor]
   [app.render-wasm.text-editor-history :as history]
   [app.util.i18n :as i18n]
   [app.util.text.content.styles :as content-styles]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

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

(t/deftest reading-selection-stays-with-one-base-group
  (let [content (editor-content [{:text "你好"} {:text "世界" :font-weight "700"}]
                                [{:text "中国" :ruby "zhōng guó"}])]
    (doseq [range [(selection 0 0 0 1) (selection 0 2 0 0)
                   (selection 0 2 0 4) (selection 1 0 1 2)]]
      (t/is (true? (text-editor/reading-selection? content range))))
    (doseq [range [(selection 0 1 0 3) (selection 0 0 1 2)
                   (selection 0 1 0 1) (selection 1 0 1 1)]]
      (t/is (false? (text-editor/reading-selection? content range))))
    (t/is (false? (text-editor/reading-selection? nil nil)))))

(t/deftest japanese-styles-follow-the-selected-span
  (let [content (editor-content
                 [{:text "日" :ruby "にち" :text-emphasis "filled-dot"}
                  {:text "本"}])]
    (t/is (= {:text-combine-upright "none"
              :text-emphasis "filled-dot"
              :text-emphasis-position "auto"
              :text-underline-position "auto"
              :ruby "にち"
              :ruby-hidden false
              :ruby-zhuyin false
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
              :text-emphasis-position "auto"
              :text-underline-position "auto"
              :ruby ""
              :ruby-hidden false
              :ruby-zhuyin false
              :ruby-size "half"
              :ruby-align "space-around"
              :ruby-overhang "auto"
              :ruby-side "over"
              :warichu "none"
              :font-features "none"
              :annotation-clearance "none"}
             (text-editor/selection-japanese-styles
              content (selection 0 1 0 2))))))

(t/deftest paragraph-profiles-follow-the-editor-selection
  (let [content {:type "root"
                 :children [{:type "paragraph-set"
                             :children [{:type "paragraph" :children [{:text "Plain"}]}
                                        {:type "paragraph" :text-language "ko"
                                         :korean-wrap "spaces-only"
                                         :children [{:text "한국어"}]}]}]}]
    (t/is (= {:text-language nil :korean-wrap nil}
             (text-editor/selection-paragraph-styles content (selection 0 0 0 0))))
    (t/is (= {:text-language "ko" :korean-wrap "spaces-only"}
             (text-editor/selection-paragraph-styles content (selection 1 1 1 1))))
    (t/is (= {:text-language :multiple :korean-wrap :multiple}
             (text-editor/selection-paragraph-styles content (selection 0 0 1 1))))
    (t/is (= {:text-language :multiple :korean-wrap :multiple}
             (text-editor/selection-paragraph-styles content (selection 1 1 0 0))))))

(t/deftest japanese-styles-report-mixed-ranges-and-follow-the-caret
  (let [content (editor-content
                 [{:text "平成"
                   :text-combine-upright "digits2"
                   :text-emphasis "filled-dot"
                   :text-emphasis-position "under-left"
                   :text-underline-position "right"
                   :ruby "へいせい"
                   :ruby-hidden true
                   :ruby-size "third"
                   :ruby-align "center"
                   :ruby-overhang "none"
                   :ruby-side "under"
                   :annotation-clearance "auto"}
                  {:text "年"
                   :ruby-hidden false
                   :ruby-zhuyin false
                   :warichu "warichu"
                   :font-features "vpal"}])]
    (t/is (= {:text-combine-upright :multiple
              :text-emphasis :multiple
              :text-emphasis-position :multiple
              :text-underline-position :multiple
              :ruby :multiple
              :ruby-hidden :multiple
              :ruby-zhuyin false
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

(t/deftest editor-history-restores-styled-content-and-selection
  (let [before {:content (editor-content [{:text "A😀B" :ruby "a"}])
                :selection (selection 0 1 0 3)}
        after  {:content (editor-content [{:text "A你好B" :ruby "a"}])
                :selection (selection 0 3 0 3)}
        edits  (history/record history/empty-history before after)
        undone (history/step edits :undo after)]
    (t/is (= before (:snapshot undone)))
    (t/is (= after (:snapshot (history/step (:history undone) :redo before))))))

(t/deftest editor-history-keeps-no-ops-out-and-discards-redo-after-editing
  (let [a {:content "A" :selection (selection 0 1 0 1)}
        b {:content "AB" :selection (selection 0 2 0 2)}
        c {:content "AC" :selection (selection 0 2 0 2)}
        edited (history/record history/empty-history a b)
        undone (:history (history/step edited :undo b))]
    (t/is (= undone (history/record undone a a)))
    (t/is (nil? (history/step (history/record undone a c) :redo c)))
    (t/is (nil? (history/step history/empty-history :undo a)))))

(t/deftest editor-history-bounds-the-number-of-saved-edits
  (let [edits (reduce (fn [edits n]
                        (history/record edits {:content n} {:content (inc n)}))
                      history/empty-history (range 150))]
    (t/is (= 100 (count (:past edits))))
    (t/is (= 50 (:content (first (:past edits)))))))

(t/deftest ^:async editor-history-groups-style-updates-and-committed-composition
  (let [edits       @#'text-editor/editing-history
        cache       @#'text-editor/shape-text-contents
        old-edits   @edits
        old-cache   @cache
        before      (editor-content [{:text "A😀B"}])
        inserted    (editor-content [{:text "A你好B"}])
        styled      (editor-content [{:text "A你好B" :ruby "nǐ hǎo"}])
        range       (selection 0 1 0 3)]
    (try
      (await
       (mock/with-mocks*
         {text-editor/text-editor-get-selection (constantly range)}
         (reset! cache {:fixture before})
         (reset! edits (assoc history/empty-history :shape-id :fixture))
         (text-editor/with-history-edit
           (fn []
             (text-editor/update-cached-content! :fixture inserted)
             (text-editor/update-cached-content! :fixture styled)))
         (t/is (= [{:content before :selection range}] (:past @edits)))
         (t/is (= styled (text-editor/get-cached-content :fixture)))
         ;; Preview updates must never enter history. Commit the whole final
         ;; reading once; a cancelled composition is a no-op and keeps redo.
         (reset! cache {:fixture before})
         (reset! edits (assoc history/empty-history :shape-id :fixture))
         (@#'text-editor/checkpoint-history)
         (swap! edits assoc :composing true)
         (@#'text-editor/record-content-history :fixture inserted)
         (t/is (empty? (:past @edits)))
         (swap! edits assoc :composing false)
         (text-editor/update-cached-content! :fixture inserted)
         (t/is (= [before] (mapv :content (:past @edits))))
         (reset! cache {:fixture before})
         (reset! edits (assoc history/empty-history :shape-id :fixture
                              :future [{:content inserted :selection range}]))
         (@#'text-editor/checkpoint-history)
         (text-editor/update-cached-content! :fixture before)
         (t/is (= [inserted] (mapv :content (:future @edits))))
         ;; Incoming content that bypasses local editor edits must not become
         ;; a local undo entry or leave redo able to overwrite the new tree.
         (text-editor/cache-shape-text-content! :fixture styled)
         (t/is (empty? (:past @edits)))
         (t/is (empty? (:future @edits)))))
      (finally
        (reset! edits old-edits)
        (reset! cache old-cache)))))

(t/deftest ^:async hanja-conversion-keeps-one-history-entry-for-the-original-selection
  ;; Fcitx Hangul sends ㅎ → 하 → 한 → 韓 in one composition. The selected
  ;; source, including its styles and UTF-16 range, must survive a single undo.
  (let [edits      @#'text-editor/editing-history
        cache      @#'text-editor/shape-text-contents
        styles     @#'text-editor/pending-caret-styles
        old-edits  @edits
        old-cache  @cache
        old-styles @styles
        range      (atom (selection 0 1 0 3))]
    (try
      (await
       (mock/with-mocks*
         {wasm/ready? (constantly true)
          wasm/internal-module #js {"_text_editor_composition_start" (fn [] nil)
                                    "_text_editor_composition_update" (fn [] nil)
                                    "_text_editor_composition_end" (fn [] nil)
                                    "_text_editor_has_focus" (fn [] 1)
                                    "_text_editor_restore_selection" (fn [& _] nil)}
          text-editor/text-editor-encode-text-pre (constantly nil)
          text-editor/text-editor-encode-text-post (constantly nil)
          text-editor/text-editor-get-selection (fn [] @range)}
         (doseq [writing-mode ["horizontal-tb" "vertical-rl"]
                 wrap ["syllable" "keep-words"]]
           (let [content (fn [text]
                           (-> (editor-content
                                [{:text "A"}
                                 {:text text :font-weight "700"}
                                 {:text "B"}])
                               (assoc-in [:children 0 :children 0 :text-language] "ko")
                               (assoc-in [:children 0 :children 0 :writing-mode] writing-mode)
                               (assoc-in [:children 0 :children 0 :korean-wrap] wrap)))
                 before  (content "😀")
                 after   (content "韓")]
             (reset! range (selection 0 1 0 3))
             (reset! cache {:fixture before})
             (reset! styles {})
             (reset! edits (assoc history/empty-history :shape-id :fixture))
             (text-editor/text-editor-composition-start)
             (doseq [preview ["ㅎ" "하" "한" "韓"]]
               (text-editor/text-editor-composition-update preview))
             (t/is (= before (text-editor/get-cached-content :fixture)))
             (t/is (empty? (:past @edits)))
             (text-editor/text-editor-composition-end "韓")
             (reset! range (selection 0 2 0 2))
             (text-editor/update-cached-content! :fixture after)
             (t/is (= [{:content before :selection (selection 0 1 0 3)}]
                      (:past @edits)))
             (let [undone (text-editor/restore-history
                           :undo identity text-editor/update-cached-content!)]
               (t/is (= before (:content undone)))
               (t/is (= before (text-editor/get-cached-content :fixture))))
             ;; Mirror the selection restored by WASM before asking for redo.
             (reset! range (selection 0 1 0 3))
             (let [redone (text-editor/restore-history
                           :redo identity text-editor/update-cached-content!)]
               (t/is (= after (:content redone)))
               (t/is (= after (text-editor/get-cached-content :fixture))))))))
      (finally
        (reset! edits old-edits)
        (reset! cache old-cache)
        (reset! styles old-styles)))))

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

(t/deftest escape-that-cancels-native-composition-keeps-text-editing
  ;; Fcitx Pinyin ends composition before releasing Escape. That key-up no
  ;; longer reports isComposing, so remember that its key-down belonged to IME.
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing false :keyCode 27} true)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing true :keyCode 27} false)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing false :keyCode 229} false))))

(t/deftest ordinary-escape-still-exits-text-editing
  (t/is (true? (@#'v3-editor/editor-escape?
                #js {:key "Escape" :isComposing false :keyCode 27} false)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Enter" :isComposing false :keyCode 13} false))))

(t/deftest ime-debug-caret-bounds-follow-viewport-zoom-and-pan
  (t/is (= {:x 210 :y 380 :width 48 :height 2}
           (@#'ime-debug/screen-rect
            {:x 100 :y 200 :width 24 :height 1}
            #js {:a 2 :b 0 :c 0 :d 2 :e 10 :f -20}))))

(t/deftest ime-debug-caret-bounds-follow-shape-rotation
  (t/is (= {:x 119 :y 140 :width 1 :height 24}
           (@#'ime-debug/screen-rect
            {:x 100 :y 200 :width 24 :height 1}
            #js {:a 0 :b 1 :c -1 :d 0 :e 320 :f 40}))))

(t/deftest ime-debug-composition-offsets-use-dom-utf16-units
  (let [prefix #js {:length 3}
        kana   #js {:length 2}
        suffix #js {:length 1}
        nodes  [prefix kana suffix]]
    ;; A prefix such as "a😀" takes three DOM units, not two code points.
    ;; The range must exclude zero-length rectangles from adjacent text nodes.
    (t/is (= [kana 0] (@#'ime-debug/text-position nodes 3 :start)))
    (t/is (= [kana 2] (@#'ime-debug/text-position nodes 5 :end)))
    (t/is (nil? (@#'ime-debug/text-position nodes 7 :end)))))

(defn- ime-surface-style
  ([platform vertical? rect]
   (ime-surface-style platform vertical? rect 1))
  ([platform vertical? rect zoom]
   (ime-surface-style platform vertical? rect zoom :chrome))
  ([platform vertical? rect zoom browser]
   (mock/with-mocks*
     {cfg/browser browser
      cfg/check-platform? (fn [candidate] (= platform candidate))
      text-editor/text-editor-get-cursor-rect (fn [] rect)}
     (let [node    #js {:style #js {}
                        :closest (fn [_] #js {:getScreenCTM (fn [] #js {:c 0 :d zoom})})}
           wrapper #js {:style #js {:transform "translate(10px, 20px)"
                                    :left "-36px" :top "72px"}}
           origin  {:x 100 :y 80 :width 900 :vertical? vertical?}
           anchor  (@#'v3-editor/update-ime-caret! node wrapper origin)]
       {:left (+ (:x origin) (js/parseFloat (.. node -style -left)))
        :right (+ (:x origin) (:width origin) (js/parseFloat (.. node -style -left)))
        :top (+ (:y origin) (js/parseFloat (.. node -style -top)))
        :writing-mode (.. node -style -writingMode)
        :transform (.. node -style -transform)
        :overflow (.. node -style -overflow)
        :padding (@#'v3-editor/ime-overlay-padding vertical? 900)
        :anchor anchor
        :wrapper-left (.. wrapper -style -left)
        :wrapper-top (.. wrapper -style -top)
        :wrapper-transform (.. wrapper -style -transform)}))))

(t/deftest ^:async macos-vertical-ime-anchor-matches-the-text-column
  (doseq [width [12 24 72]
          browser [:chrome :firefox :edge]]
    (let [rect   {:x 400 :y 180 :width width :height 1}
          result (await (ime-surface-style :macos true rect 1 browser))]
      ;; vertical-rl puts the caret cell immediately left of the surface's
      ;; right edge. Match the WASM column without a horizontal offset.
      (t/is (= (+ (:x rect) (:width rect)) (:right result)))
      (t/is (= (:y rect) (:top result)))
      (t/is (= "vertical-rl" (:writing-mode result)))
      (t/is (= {:left 900 :right 0} (:padding result)))
      (t/is (= "clip" (:overflow result)))
      (t/is (true? (:vertical? (:anchor result))))
      (t/is (= "" (:wrapper-left result)))
      (t/is (= "" (:wrapper-top result)))
      (t/is (= "" (:wrapper-transform result))))))

(t/deftest ^:async macos-safari-vertical-ime-anchor-clears-the-text-column
  ;; Safari's native candidate window is horizontal even for vertical text.
  ;; Its anchor needs room to the right, including at the text shape's edge.
  (doseq [browser [:safari :safari-16 :safari-17 :safari-18 :safari-26]
          width [12 24 72]]
    (let [rect   {:x 400 :y 180 :width width :height 1}
          result (await (ime-surface-style :macos true rect 1 browser))]
      (t/is (> (- (:right result) width) (+ (:x rect) width)))
      (t/is (= (:y rect) (:top result)))
      (t/is (= {:left 900 :right 900} (:padding result)))
      (t/is (= "vertical-rl" (:writing-mode result)))
      (t/is (= "clip" (:overflow result))))))

(t/deftest ^:async macos-vertical-ime-character-bounds-stay-nonempty
  ;; Chromium derives a vertical character rectangle from integer caret y
  ;; positions. Subpixel advances become empty rectangles, which macOS uses
  ;; to position its candidate window. Exercise long compositions and zoom.
  (doseq [width [12 24 72]
          zoom [0.25 1 4]
          browser [:chrome :firefox :safari-26]]
    (let [rect   {:x 400 :y 180 :width width :height 1}
          result (await (ime-surface-style :macos true rect zoom browser))
          scale  (js/parseFloat (subs (:transform result) 7))
          step   (* width scale zoom)
          ys     (map #(js/Math.floor (+ 180 (* % step))) (range 81))]
      (t/is (every? pos? (map - (rest ys) ys))))))

(t/deftest ^:async windows-vertical-ime-anchor-matches-the-text-column
  (doseq [width [12 24 72]
          browser [:chrome :edge :firefox]]
    (let [rect   {:x 400 :y 180 :width width :height 1}
          result (await (ime-surface-style :windows true rect 1 browser))]
      ;; Windows opens vertical candidates to the left of the composition
      ;; range. Keep that range in the text column so candidates clear it.
      (t/is (= (+ (:x rect) width) (:right result)))
      (t/is (= (:y rect) (:top result)))
      (t/is (= "vertical-rl" (:writing-mode result)))
      (t/is (= "scaleY(0.001)" (:transform result))))))

(t/deftest ^:async linux-vertical-ime-anchor-stays-right-of-the-text-column
  (doseq [browser [:chrome :firefox :safari]]
    (let [rect   {:x 400 :y 180 :width 24 :height 1}
          result (await (ime-surface-style :linux true rect 1 browser))]
      ;; vertical-rl places the DOM caret in the rightmost cell of the surface.
      (t/is (> (- (:right result) (:width rect)) (+ (:x rect) (:width rect))))
      (t/is (= (:y rect) (:top result)))
      (t/is (= {:left 0 :right 900} (:padding result)))
      (t/is (= "vertical-rl" (:writing-mode result)))
      (t/is (= "scaleY(0.001)" (:transform result)))
      (t/is (= "" (:overflow result))))))

(t/deftest ^:async horizontal-ime-anchor-stays-on-the-caret
  (doseq [platform [:macos :linux :windows]
          browser [:chrome :firefox :safari]]
    (let [rect   {:x 400 :y 180 :width 1 :height 24}
          result (await (ime-surface-style platform false rect 1 browser))]
      (t/is (= (:x rect) (:left result)))
      (t/is (= (:y rect) (:top result)))
      (t/is (= {:left 0 :right 0} (:padding result)))
      (t/is (= "" (:writing-mode result)))
      (t/is (= "scaleX(0.001)" (:transform result))))))

(t/deftest ^:async macos-vertical-ime-follows-the-caret-in-layout
  (let [anchor  {:x 400 :y 180 :width 24 :height 1 :vertical? true}
        current (atom {:x 400 :y 252 :width 24 :height 1})
        wrapper #js {:style #js {:transform ""}}]
    (await
     (mock/with-mocks*
       {cfg/check-platform? (fn [candidate] (= candidate :macos))
        text-editor/text-editor-get-cursor-rect (fn [] @current)}
       (@#'v3-editor/follow-ime-caret! wrapper anchor)
       (t/is (= "72px" (.. wrapper -style -top)))
       (t/is (= "0px" (.. wrapper -style -left)))
       (t/is (= "" (.. wrapper -style -transform)))
       ;; Moving into another vertical column resets the inline position.
       (reset! current {:x 364 :y 180 :width 24 :height 1})
       (@#'v3-editor/follow-ime-caret! wrapper anchor)
       (t/is (= "0px" (.. wrapper -style -top)))
       (t/is (= "-36px" (.. wrapper -style -left)))))))

(t/deftest ^:async other-ime-directions-keep-following-with-a-transform
  (doseq [[platform vertical?] [[:linux true] [:linux false] [:macos false]
                                [:windows true] [:windows false]]]
    (let [anchor  {:x 400 :y 180 :vertical? vertical?}
          wrapper #js {:style #js {}}]
      (await
       (mock/with-mocks*
         {cfg/check-platform? (fn [candidate] (= candidate platform))
          text-editor/text-editor-get-cursor-rect (fn [] {:x 424 :y 252})}
         (@#'v3-editor/follow-ime-caret! wrapper anchor)
         (t/is (= "translate(24px, 72px)" (.. wrapper -style -transform))))))))

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

(t/deftest language-and-wrapping-only-change-selected-paragraphs
  (let [content (editor-content [{:text "日本"}] [{:text "한국어"}] [{:text "中文"}])
        result  (text-editor/apply-paragraph-attrs-to-range
                 content (selection 1 0 1 0)
                 {:text-language "ko" :korean-wrap "spaces-only"})
        paragraphs (-> result :children first :children)]
    (t/is (= [nil "ko" nil] (mapv :text-language paragraphs)))
    (t/is (= [nil "spaces-only" nil] (mapv :korean-wrap paragraphs)))
    (t/is (= result
             (text-editor/merge-exported-texts-into-content
              result [[{:p 0 :s 0 :t "日本"}]
                      [{:p 1 :s 0 :t "한국어"}]
                      [{:p 2 :s 0 :t "中文"}]]))))
  (let [content (assoc-in (editor-content [{:text "한글"}])
                          [:children 0 :children 0 :text-language] "ko")
        result  (text-editor/merge-exported-texts-into-content
                 content [[{:p 0 :s 0 :t "한"}] [{:p 0 :s 0 :t "글"}]])]
    (t/is (= ["ko" "ko"] (mapv :text-language (-> result :children first :children))))))

(t/deftest paragraph-language-binary-header-matches-rust
  (let [dview (js/DataView. (js/ArrayBuffer. tc/PARAGRAPH-ATTR-U8-SIZE))
        end   (@#'tc/write-paragraph 0 dview
                                     {:text-language "zh-Hant-TW" :korean-wrap "spaces-only"
                                      :line-height "1.5" :letter-spacing "2"})]
    (t/is (= 20 end))
    (t/is (= 1 (.getUint8 dview 6)))
    (t/is (= 0 (.getUint8 dview 7)))
    (t/is (= 1.5 (.getFloat32 dview 8 true)))
    (t/is (= 2 (.getFloat32 dview 12 true)))
    (t/is (= 10 (.getUint32 dview 16 true)))))

(t/deftest merging-away-original-spans-keeps-the-surviving-paragraph-profile
  (let [content (-> (editor-content [{:text "日"}] [{:text "한"}])
                    (assoc-in [:children 0 :children 0 :text-language] "ja")
                    (assoc-in [:children 0 :children 1 :text-language] "ko")
                    (assoc-in [:children 0 :children 1 :korean-wrap] "spaces-only"))
        result  (text-editor/merge-exported-texts-into-content
                 content [[{:pp 0 :p 1 :s 0 :t "한"}]])
        para    (get-in result [:children 0 :children 0])]
    (t/is (= "ja" (:text-language para)))
    (t/is (not (contains? para :korean-wrap)))
    (t/is (= "한" (get-in para [:children 0 :text])))))

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
   :text-emphasis-position 14
   :text-underline-position 15
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

(t/deftest paragraph-wrap-modes-have-stable-distinct-protocol-bytes
  (doseq [[mode expected] [[nil 0] ["syllable" 0] ["spaces-only" 1] ["keep-words" 2] ["invalid" 0]]]
    (let [dview (js/DataView. (js/ArrayBuffer. tc/PARAGRAPH-ATTR-U8-SIZE))]
      (@#'tc/write-paragraph 0 dview {:text-language "ko" :korean-wrap mode})
      (t/is (= expected (.getUint8 dview 6)))
      (t/is (= 0 (.getUint8 dview 7)))
      (t/is (= 2 (.getUint32 dview 16 true))))))

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
    (t/is (pos? (ruby-length (serialized-span {:text "日" :ruby "にち" :ruby-hidden false}))))
    ;; A stored nil reading sends no bytes, as an absent one does.
    (t/is (= 0 (ruby-length (serialized-span {:text "日" :ruby nil}))))))

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
           (cfnt/resolve-ambiguous-cjk #{:japanese :han :cjk-punctuation} nil "zh")))
  (t/is (= #{:japanese}
           (cfnt/resolve-ambiguous-cjk #{:japanese :han} nil "en"))))

(t/deftest resolve-hangul-wins-over-locale
  (t/is (= #{:korean}
           (cfnt/resolve-ambiguous-cjk #{:korean :han} nil "ja"))))

(t/deftest resolve-han-only-uses-locale
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} nil "ja")))
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} nil "ja_paid")))
  (t/is (= #{:korean}   (cfnt/resolve-ambiguous-cjk #{:han} nil "ko")))
  (t/is (= #{:chinese}  (cfnt/resolve-ambiguous-cjk #{:han} nil "zh_cn"))))

(t/deftest resolve-han-only-defaults-to-chinese
  ;; Without kana, hangul or a CJK locale, Han resolves to Chinese (Noto Sans SC).
  (t/is (= #{:chinese} (cfnt/resolve-ambiguous-cjk #{:han} nil "en")))
  (t/is (= #{:chinese} (cfnt/resolve-ambiguous-cjk #{:han} nil nil))))

(t/deftest resolve-punctuation-only
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:cjk-punctuation} nil "ja")))
  (t/is (= #{:chinese}  (cfnt/resolve-ambiguous-cjk #{:cjk-punctuation} nil "en"))))

(t/deftest resolve-paragraph-language-wins
  ;; The paragraph language decides Han, whatever the content or locale.
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} "ja" "en")))
  (t/is (= #{:chinese :japanese}
           (cfnt/resolve-ambiguous-cjk #{:japanese :han} "zh-Hans" "ja")))
  (t/is (= #{:korean} (cfnt/resolve-ambiguous-cjk #{:han} "ko" nil)))
  ;; A non-CJK paragraph language leaves the content and locale to decide.
  (t/is (= #{:japanese} (cfnt/resolve-ambiguous-cjk #{:han} "en-GB" "ja"))))

(t/deftest resolve-regional-chinese
  (t/is (= #{:chinese-traditional} (cfnt/resolve-ambiguous-cjk #{:han} "zh-Hant-TW" nil)))
  (t/is (= #{:chinese-traditional} (cfnt/resolve-ambiguous-cjk #{:han} "zh-TW" nil)))
  (t/is (= #{:chinese-hongkong} (cfnt/resolve-ambiguous-cjk #{:han} "zh-Hant-HK" nil)))
  (t/is (= #{:chinese} (cfnt/resolve-ambiguous-cjk #{:han} "zh-CN" nil)))
  ;; App locales use underscores.
  (t/is (= #{:chinese-traditional} (cfnt/resolve-ambiguous-cjk #{:han} nil "zh_hant"))))

(t/deftest paragraph-language-selects-the-han-fallback-font
  (let [content (fn [language]
                  {:children
                   [{:children
                     [{:text-language language
                       :children [{:text "東京都"}]}]}]})
        fonts   #(font-ids (api/fonts-from-text-content (content %) false))]
    (with-redefs [i18n/locale (atom "en")]
      (t/is (contains? (fonts "ja") "gfont-noto-sans-jp"))
      (t/is (not (contains? (fonts "ja") "gfont-noto-sans-sc")))
      (t/is (contains? (fonts "zh-Hant-TW") "gfont-noto-sans-tc"))
      (t/is (contains? (fonts nil) "gfont-noto-sans-sc")))))

(t/deftest resolve-leaves-unambiguous-sets-alone
  (t/is (= #{:latin-ext} (cfnt/resolve-ambiguous-cjk #{:latin-ext} nil "ja")))
  (t/is (= #{} (cfnt/resolve-ambiguous-cjk #{} nil "ja")))
  ;; Other detected languages survive resolution.
  (t/is (= #{:japanese :cyrillic}
           (cfnt/resolve-ambiguous-cjk #{:han :cyrillic} nil "ja"))))

(t/deftest resolve-han-unification
  ;; A kanji-only Japanese address resolves to Japanese under a ja locale.
  (t/is (= #{:japanese}
           (cfnt/resolve-ambiguous-cjk (langs "東京都渋谷区神南一丁目") nil "ja"))))

(t/deftest wasm-enum-exports-match-the-japanese-value-table
  (doseq [[attr values] cjk/enum-values]
    (let [exported (js->clj (unchecked-get wasm/serializers (name attr)))]
      (t/is (= values (mapv key (sort-by val exported))) (name attr)))))

(t/deftest unset-or-unknown-japanese-values-serialize-as-the-default
  (t/is (= 0 (sr/translate-text-layout-enum :ruby-side nil)))
  (t/is (= 0 (sr/translate-text-layout-enum :ruby-side "")))
  (t/is (= 0 (sr/translate-text-layout-enum :ruby-side "left")))
  (t/is (= 1 (sr/translate-text-layout-enum :ruby-side "under")))
  (t/is (= 1 (sr/translate-text-layout-enum :ruby-side :under))))


(t/deftest decoration-positions-use-the-former-padding-bytes
  (let [view (serialized-span {:text "中文" :text-emphasis-position "under-left"
                               :text-underline-position "under-right"})]
    (t/is (= 4 (span-byte view :text-emphasis-position)))
    (t/is (= 5 (span-byte view :text-underline-position)))
    (t/is (= 16 (.getFloat32 view 16 true)))))


(t/deftest decoration-edits-preserve-an-existing-reading-as-one-group
  (let [paragraph {:type "paragraph" :children [{:text "你好" :ruby "nǐ hǎo"}
                                                {:text "世界" :font-weight "700"}]}
        result (text-editor/apply-attrs-to-paragraph paragraph 1 3 {:text-emphasis-position "under-left"})]
    (t/is (= [{:text "你好" :ruby "nǐ hǎo" :text-emphasis-position "under-left"}
              {:text "世" :font-weight "700" :text-emphasis-position "under-left"}
              {:text "界" :font-weight "700"}]
             (:children result)))))


(t/deftest dom-editor-style-data-keeps-decoration-positions
  (let [attrs {:text-emphasis-position "under-left" :text-underline-position "under-right"}
        styles (content-styles/attrs->styles attrs)]
    (t/is (= "under-left" (aget styles "--text-emphasis-position")))
    (t/is (= "under-right" (aget styles "--text-underline-position")))
    (t/is (= attrs (content-styles/styles->attrs (js->clj styles))))))


(t/deftest a-collapsed-decoration-range-does-not-restyle-a-reading
  (let [paragraph {:type "paragraph" :children [{:text "你好" :ruby "nǐ hǎo"}]}]
    (t/is (= paragraph (text-editor/apply-attrs-to-paragraph paragraph 1 1 {:text-emphasis-position "under-left"})))))

(t/deftest zhuyin-mode-round-trips-in-existing-span-flags
  (doseq [[attrs expected] [[{} 0] [{:ruby-side "under"} 1]
                            [{:ruby-zhuyin true} 2]
                            [{:ruby-zhuyin true :ruby-side "under"} 3]]]
    (t/is (= expected (span-byte (serialized-span attrs) :ruby-side)))))

(t/deftest zhuyin-selection-requires-a-whole-single-han-base
  (let [content (editor-content [{:text "你好"} {:text "𠀀"}] [{:text "中" :ruby "ㄓㄨㄥ" :ruby-zhuyin true}])]
    (t/is (true? (text-editor/zhuyin-selection? content (selection 0 0 0 1))))
    (t/is (true? (text-editor/zhuyin-selection? content (selection 0 2 0 4))))
    (t/is (true? (text-editor/zhuyin-selection? content (selection 1 0 1 1))))
    (t/is (false? (text-editor/zhuyin-selection? content (selection 0 0 0 2))))
    (t/is (false? (text-editor/zhuyin-selection? content (selection 0 2 0 3))))))

(t/deftest dom-style-data-keeps-zhuyin-as-a-boolean
  (let [attrs {:ruby-zhuyin true :ruby "ㄋㄧˇ"}
        encoded (content-styles/attrs->styles attrs)
        decoded (content-styles/styles->attrs (js->clj encoded))]
    (t/is (true? (:ruby-zhuyin decoded)))
    (t/is (= "ㄋㄧˇ" (:ruby decoded)))))

(t/deftest zhuyin-export-retains-default-fonts-when-optional-span-fonts-are-absent
  (let [entry (@#'api/zhuyin-glyph-entry
               {:text "中" :ruby "ㄓ" :ruby-zhuyin true}
               {:start-pos 0 :end-pos 1 :x 10 :y 20 :width 6 :height 6})]
    (t/is (= "sourcesanspro" (:font-family entry)))
    (t/is (= "ㄓ" (:text entry)))
    (t/is (= 26 (:y entry)))))
