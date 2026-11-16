;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.render-wasm.text-editor-history-test
  "Undo and redo inside the WASM text editor: the history of one editing
   session, with grouped edits and IME compositions."
  (:require
   [app.common.render-wasm.wasm :as wasm]
   [app.render-wasm.text-editor :as text-editor]
   [app.render-wasm.text-editor-history :as history]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

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

(t/deftest editor-history-restores-styled-content-and-selection
  (let [before {:content (editor-content [{:text "A😀B" :font-weight "700"}])
                :selection (selection 0 1 0 3)}
        after  {:content (editor-content [{:text "A你好B" :font-weight "700"}])
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
        styled      (editor-content [{:text "A你好B" :font-weight "700"}])
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
         ;; text once; a cancelled composition is a no-op and keeps redo.
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
         (let [content (fn [text]
                         (editor-content
                          [{:text "A"}
                           {:text text :font-weight "700"}
                           {:text "B"}]))
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
             (t/is (= after (text-editor/get-cached-content :fixture)))))))
      (finally
        (reset! edits old-edits)
        (reset! cache old-cache)
        (reset! styles old-styles)))))

(t/deftest ^:async cancelled-composition-restores-styled-source-without-changing-history
  (let [edits      @#'text-editor/editing-history
        cache      @#'text-editor/shape-text-contents
        old-edits  @edits
        old-cache  @cache
        source     (editor-content [{:text "A"}
                                    {:text "中" :fills [{:fill-color "#b84821"}]}
                                    {:text "😀文" :font-weight "700"}
                                    {:text "B"}])
        deleted    (editor-content [{:text "AB"}])
        engine     (atom source)
        range      (atom nil)
        redo       [{:content (editor-content [{:text "你好"}])
                     :selection (selection 0 2 0 2)}]]
    (try
      (await
       (mock/with-mocks*
         {wasm/ready? (constantly true)
          wasm/internal-module
          #js {"_text_editor_composition_start" (fn [] nil)
               "_text_editor_composition_end" (fn [] (reset! engine deleted))
               "_text_editor_restore_selection"
               (fn [ap ao fp fo] (reset! range (selection ap ao fp fo)))}
          text-editor/text-editor-encode-text-pre (constantly nil)
          text-editor/text-editor-encode-text-post (constantly nil)
          text-editor/text-editor-get-selection (fn [] @range)}
         (doseq [selected [(selection 0 1 0 5) (selection 0 5 0 1)
                           (selection 0 1 0 1)]]
           (reset! engine source)
           (reset! range selected)
           (reset! cache {:fixture source})
           (reset! edits (assoc history/empty-history :shape-id :fixture :future redo))
           (text-editor/text-editor-composition-start)
           (text-editor/finish-composition
            "" identity
            (fn [shape-id content]
              (reset! engine content)
              (text-editor/cache-shape-text-content! shape-id content)))
           ;; The usual compositionend sync exports the current WASM tree.
           (text-editor/update-cached-content! :fixture @engine)
           (t/is (= source @engine))
           (t/is (= selected @range))
           (t/is (= source (text-editor/get-cached-content :fixture)))
           (t/is (empty? (:past @edits)))
           (t/is (= redo (:future @edits)))
           (t/is (not (:composing @edits))))))
      (finally
        (reset! edits old-edits)
        (reset! cache old-cache)))))
