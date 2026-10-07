;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.copy-as-svg-test
  "Regression tests for the Copy as SVG action (issue #838).

  The bug: when multiple shapes were selected, `generate-markup` emitted
  several sibling `<svg>` roots concatenated with newlines. External SVG
  parsers (Inkscape, browsers) only read the first root, so multi-shape
  selection appeared to copy only one shape. The fix wraps 2+ shapes in a
  single `<svg>` root with a combined viewBox."
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.shape :as cts]
   [app.util.code-gen.markup-svg :as svg]
   [cljs.test :refer [deftest is testing] :include-macros true]))

(defn- setup-shapes
  "Build a file with `n` sample rectangles on the current page.
  Returns a map with `:objects` and `:shapes` keys, mirroring the inputs
  that `copy-selected-svg` feeds into `generate-markup`."
  [labels]
  (let [file    (reduce (fn [f label]
                          (cths/add-sample-shape f label))
                        (cthf/sample-file :file1 :page-label :page1)
                        labels)
        page    (cthf/current-page file)
        objects (:objects page)
        shapes  (mapv #(get objects (cthi/id %)) labels)]
    {:objects objects :shapes shapes}))

(defn- count-matches
  [re s]
  (count (re-seq re s)))

(def ^:private strip-defaults
  {:fills [{:fill-color "#112233" :fill-opacity 1}]
   :font-family "Noto Sans CJK JP"
   :font-size "20"
   :font-weight "400"})

(defn- setup-text
  "A text shape with the given position-data `strips` (over
  `strip-defaults`), with its objects and selection."
  [{:keys [width height]} & strips]
  (let [shape   (-> (cts/setup-shape {:type :text :x 10 :y 20 :width width :height height})
                    (assoc :name "Text"
                           :position-data (mapv #(merge strip-defaults %) strips)))
        file    (cths/add-sample-shape
                 (cthf/sample-file :file1 :page-label :page1)
                 :text
                 shape)
        objects (:objects (cthf/current-page file))]
    {:objects objects
     :shapes [(get objects (cthi/id :text))]}))

(defn- vertical-strip
  [attrs]
  (merge {:x 20 :y 100 :width 24 :height 80
          :writing-mode "vertical-rl"
          :text-orientation "upright"}
         attrs))

(defn- horizontal-strip
  [attrs]
  (merge {:x 20 :y 60 :width 100 :height 20
          :writing-mode "horizontal-tb"}
         attrs))

(defn- emphasis-mark
  "A mark entry as the renderer emits it: the mark glyph in its em box."
  [x y]
  {:x x :y y :width 10 :height 10 :font-size "10px" :emphasis-mark true :text "•"})

(deftest empty-selection-yields-empty-string
  (is (= "" (svg/generate-markup {} []))))

(deftest single-shape-produces-one-svg-root
  (testing "Regression guard: the single-shape path stays unchanged"
    (let [{:keys [objects shapes]} (setup-shapes [:rect-1])
          markup (svg/generate-markup objects shapes)]
      (is (string? markup))
      (is (pos? (count markup)))
      (is (= 1 (count-matches #"<svg\b" markup))
          "single shape should produce exactly one <svg> root"))))

(deftest multi-shape-produces-single-svg-root
  (testing "Fix for #838: multiple shapes share one outer <svg>"
    (let [{:keys [objects shapes]} (setup-shapes [:rect-1 :rect-2 :rect-3])
          markup (svg/generate-markup objects shapes)]
      (is (string? markup))
      (is (pos? (count markup)))
      (is (= 1 (count-matches #"<svg\b" markup))
          "multi-select must NOT emit multiple <svg> roots")
      (is (= 1 (count-matches #"</svg>" markup))
          "multi-select must NOT emit multiple </svg> closing tags"))))

(deftest vertical-text-svg-preserves-browser-layout-properties
  (testing "Static SVG carries the vertical writing properties used by browser exports"
    (let [{:keys [objects shapes]} (setup-text {:width 40 :height 100}
                                               (vertical-strip {:font-features "vpal" :text "うA"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"writing-mode:vertical-rl" markup))
      (is (re-find #"text-orientation:upright" markup))
      (is (re-find #"text-autospace:normal" markup))
      (is (re-find #"font-feature-settings:&quot;vpal&quot;" markup))
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest emphasis-svg-draws-each-mark-in-its-box
  (testing "Static SVG draws the marks the renderer placed, centred in their boxes"
    (let [{:keys [objects shapes]} (setup-text {:width 60 :height 100}
                                               (vertical-strip {:text "強調"})
                                               (emphasis-mark 44 40)
                                               (emphasis-mark 44 60))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"強調" markup))
      (is (= 2 (count-matches #">•<" markup)) "one text element per mark")
      (is (re-find #"x=\"49\"" markup) "marks centre on their box")
      (is (re-find #"text-anchor=\"middle\"" markup))
      (is (re-find #"font-size:10px" markup))
      (is (re-find #"fill:url\(#fill-1-[^)]+-1\)" markup)
          "a mark references its generated per-strip fill")
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest vertical-warichu-svg-draws-each-sub-line-at-half-size
  (testing "Static SVG draws each renderer-split warichu sub-line in its own strip"
    (let [{:keys [objects shapes]} (setup-text {:width 60 :height 100}
                                               (vertical-strip {:x 30 :width 10 :height 40
                                                                :text "割注" :warichu "warichu"})
                                               (vertical-strip {:x 20 :width 10 :height 40
                                                                :text "入り" :warichu "warichu"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"x=\"35\"[^>]*>割注<" markup) "first sub-line on the right half")
      (is (re-find #"x=\"25\"[^>]*>入り<" markup) "second sub-line on the left half")
      (is (= 2 (count-matches #"font-size:10px" markup)))
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest horizontal-warichu-svg-draws-each-sub-line-at-half-size
  (testing "Static SVG draws horizontal warichu sub-lines from their top edges"
    (let [{:keys [objects shapes]} (setup-text {:width 120 :height 40}
                                               (horizontal-strip {:width 40 :height 10 :y 50
                                                                  :text "割注" :warichu "warichu"})
                                               (horizontal-strip {:width 40 :height 10 :y 60
                                                                  :text "入り" :warichu "warichu"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"割注" markup))
      (is (re-find #"入り" markup))
      (is (re-find #"writing-mode:horizontal-tb" markup))
      (is (= 2 (count-matches #"dominant-baseline=.?hanging" markup)))
      (is (= 2 (count-matches #"font-size:10px" markup))))))

(deftest vertical-ruby-svg-emits-static-annotation
  (testing "Static SVG keeps ruby visible without falling back to foreignObject"
    (let [{:keys [objects shapes]} (setup-text {:width 60 :height 100}
                                               (vertical-strip {:text "漢字" :ruby "かんじ"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"漢字" markup))
      (is (re-find #"かんじ" markup))
      (is (re-find #"font-size:10px" markup))
      (is (re-find #"text-orientation:upright" markup))
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest western-ruby-svg-is-one-word
  (testing "A Latin reading turns sideways and is centred, not stretched to the base"
    (let [{:keys [objects shapes]} (setup-text {:width 60 :height 100}
                                               (vertical-strip {:text "編集者" :ruby "editor"
                                                                :text-orientation "mixed"})
                                               (horizontal-strip {:y 160 :text "編集者"
                                                                  :ruby "editor"}))
          markup (svg/generate-markup objects shapes)
          rubies (re-seq #"<text[^>]*>editor<" markup)]
      (is (= 2 (count rubies)))
      (is (every? #(re-find #"text-anchor=.?middle" %) rubies))
      (is (not-any? #(re-find #"textLength|text-length" %) rubies))
      (is (re-find #"text-orientation:mixed[^>]*>editor<" markup)))))

(deftest vertical-digit-composite-svg-combines-its-own-strip
  (testing "SVG applies text-combine-upright per text element, so each digit-run strip combines"
    (let [{:keys [objects shapes]} (setup-text {:width 40 :height 120}
                                               (vertical-strip {:y 40 :height 40 :text "平成"
                                                                :text-combine-upright "digits2"})
                                               (vertical-strip {:y 60 :height 20 :text "31"
                                                                :text-combine-upright "digits2"})
                                               (vertical-strip {:y 80 :height 20 :text "年"
                                                                :text-combine-upright "digits2"}))
          markup (svg/generate-markup objects shapes)]
      (is (= 1 (count-matches #"text-combine-upright:all" markup))
          "only the 31 strip combines")
      (is (re-find #"text-combine-upright:all[^>]*>31<" markup))
      (is (not (re-find #"digits" markup))))))
