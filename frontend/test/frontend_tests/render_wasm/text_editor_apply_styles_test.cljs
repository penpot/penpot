;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.text-editor-apply-styles-test
  "Unit tests for applying styles to a selection of text spans.

   `apply-attrs-to-paragraph` splits the affected spans at the selection
   boundaries and either merges a map of attrs onto the selected spans or, when
   given a function, transforms each selected span. The function form is what
   fill operations (add, remove, reorder...) rely on to preserve each span's
   existing fills instead of overwriting them."
  (:require
   [app.render-wasm.text-editor :as text-editor]
   [cljs.test :as t :include-macros true]))

(def ^:private apply-attrs-to-paragraph text-editor/apply-attrs-to-paragraph)

(defn- span [text fills]
  {:text text :fills fills})

(def ^:private red {:fill-color "#ff0000" :fill-opacity 1})
(def ^:private green {:fill-color "#00ff00" :fill-opacity 1})

(defn- prepend-fill
  "Mirrors the `add-fill` node transform: prepend a fill to the span's fills."
  [fill]
  (fn [node] (update node :fills #(into [fill] %))))

(t/deftest apply-map-attrs
  (t/testing "a map of attrs is merged onto the selected span"
    (let [para   {:children [(span "hello world" [red])]}
          result (apply-attrs-to-paragraph para 0 5 {:font-size "20"})]
      (t/is (= [(assoc (span "hello" [red]) :font-size "20")
                (span " world" [red])]
               (:children result))))))

(t/deftest apply-fn-preserves-existing-fills
  (t/testing "the fn form prepends to the selected span's existing fills"
    (let [para   {:children [(span "hello world" [red])]}
          result (apply-attrs-to-paragraph para 0 5 (prepend-fill green))]
      (t/is (= [(span "hello" [green red])
                (span " world" [red])]
               (:children result)))))

  (t/testing "each selected span keeps its own fills across multiple spans"
    (let [para   {:children [(span "foo" [red])
                             (span "bar" [green])]}
          ;; select the whole paragraph (6 chars) and prepend green
          result (apply-attrs-to-paragraph para 0 6 (prepend-fill green))]
      (t/is (= [(span "foo" [green red])
                (span "bar" [green green])]
               (:children result)))))

  (t/testing "a span outside the selection is left untouched"
    (let [para   {:children [(span "abcdef" [red])]}
          ;; select only "cd"
          result (apply-attrs-to-paragraph para 2 4 (prepend-fill green))]
      (t/is (= [(span "ab" [red])
                (span "cd" [green red])
                (span "ef" [red])]
               (:children result))))))

(defn- content [paras]
  {:children [{:children paras}]})

(defn- para [spans]
  {:children spans})

(defn- selection [start-para start-offset end-para end-offset]
  {:start-para start-para :start-offset start-offset
   :end-para end-para :end-offset end-offset})

(t/deftest selection-fills
  (t/testing "a selection where every span shares the same fills returns that vector"
    (let [c (content [(para [(span "hello world" [red])])])]
      (t/is (= [red] (text-editor/selection-fills c (selection 0 0 0 5))))))

  (t/testing "a selection within a single span returns that span's fills"
    (let [c (content [(para [(span "abcdef" [red])])])]
      (t/is (= [red] (text-editor/selection-fills c (selection 0 2 0 4))))))

  (t/testing "a selection spanning spans with different fills is :multiple"
    (let [c (content [(para [(span "foo" [red])
                             (span "bar" [green])])])]
      (t/is (= :multiple (text-editor/selection-fills c (selection 0 0 0 6))))))

  (t/testing "a selection restricted to one uniform span is not :multiple"
    (let [c (content [(para [(span "foo" [red])
                             (span "bar" [green])])])]
      (t/is (= [green] (text-editor/selection-fills c (selection 0 3 0 6))))))

  (t/testing "a selection across paragraphs with the same fills returns that vector"
    (let [c (content [(para [(span "foo" [red])])
                      (para [(span "bar" [red])])])]
      (t/is (= [red] (text-editor/selection-fills c (selection 0 0 1 3))))))

  (t/testing "a collapsed selection has no selected spans"
    (let [c (content [(para [(span "hello" [red])])])]
      (t/is (nil? (text-editor/selection-fills c (selection 0 2 0 2)))))))

(t/deftest apply-attrs-joins-spans-left-with-the-same-style
  (t/testing "extending a style over its neighbour leaves one span"
    (let [para   {:children [{:text "ab"} {:text "12" :text-combine-upright "all"} {:text "3c"}]}
          result (apply-attrs-to-paragraph para 4 5 {:text-combine-upright "all"})]
      (t/is (= [{:text "ab"} {:text "123" :text-combine-upright "all"} {:text "c"}]
               (:children result)))))

  (t/testing "resetting a style to its default rejoins the text"
    (let [para   {:children [{:text "ab"} {:text "cd" :text-emphasis "filled-dot"} {:text "ef"}]}
          result (apply-attrs-to-paragraph para 2 4 {:text-emphasis "none"})]
      (t/is (= [{:text "abcdef"}] (:children result))))))

(defn- editor-selection
  "A WASM editor selection from `start` to `end` in the first paragraph."
  [start end]
  {:anchor-para 0 :anchor-offset start :focus-para 0 :focus-offset end})

(def ^:private reading-text
  "日本 has one reading over two spans, the second with emphasis; 語 has
  none; 天 has a reading of its own, without a group."
  (content [(para [{:text "日" :ruby "にほん" :ruby-group "g"}
                   {:text "本" :ruby "にほん" :ruby-group "g" :text-emphasis "filled-sesame"}
                   {:text "語"}
                   {:text "を" :text-emphasis "filled-dot"}
                   {:text "天" :ruby "てん"}])]))

(t/deftest a-reading-takes-text-of-several-styles
  (t/is (text-editor/reading-selection?
         (content [(para [{:text "日"} {:text "本" :text-emphasis "filled-dot"}])])
         (editor-selection 0 2))
        "emphasis on part of the text does not block a reading"))

(t/deftest a-reading-edits-a-whole-base-only
  (t/is (text-editor/reading-selection? reading-text (editor-selection 0 2))
        "the whole base of a reading over two spans")
  (t/is (not (text-editor/reading-selection? reading-text (editor-selection 1 2)))
        "part of a base")
  (t/is (not (text-editor/reading-selection? reading-text (editor-selection 1 3)))
        "a base and the text after it")
  (t/is (text-editor/reading-selection? reading-text (editor-selection 2 4))
        "text of two styles without readings")
  (t/is (text-editor/reading-selection? reading-text (editor-selection 4 5))
        "the whole of a reading without a group"))

(t/deftest a-warichu-note-takes-one-span
  (t/is (text-editor/single-span-selection? reading-text (editor-selection 2 3)))
  (t/is (not (text-editor/single-span-selection? reading-text (editor-selection 2 4)))))

(t/deftest emphasis-on-part-of-a-grouped-reading-keeps-one-base
  (let [paragraph {:children [{:text "日本" :ruby "にほん" :ruby-group "g"} {:text "語"}]}
        result    (apply-attrs-to-paragraph paragraph 1 2 {:text-emphasis "filled-dot"})]
    (t/is (= [{:text "日" :ruby "にほん" :ruby-group "g"}
              {:text "本" :ruby "にほん" :ruby-group "g" :text-emphasis "filled-dot"}
              {:text "語"}]
             (:children result)))))

(t/deftest emphasis-on-part-of-a-reading-without-a-group-covers-its-base
  (let [paragraph {:children [{:text "日本" :ruby "にほん"} {:text "語"}]}
        result    (apply-attrs-to-paragraph paragraph 1 2 {:text-emphasis "filled-dot"})]
    (t/is (= [{:text "日本" :ruby "にほん" :text-emphasis "filled-dot"} {:text "語"}]
             (:children result))
          "splitting it would repeat the reading")))
