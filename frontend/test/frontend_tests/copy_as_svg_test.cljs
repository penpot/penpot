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
  "A text shape whose single position-data strip is `strip` (over
  `strip-defaults`), with its objects and selection."
  [{:keys [width height]} strip]
  (let [shape   (-> (cts/setup-shape {:type :text :x 10 :y 20 :width width :height height})
                    (assoc :name "Text"
                           :position-data [(merge strip-defaults strip)]))
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

(defn- setup-vertical-warichu-text
  [text]
  (setup-text {:width 60 :height 100}
              (vertical-strip {:height 40 :text text :warichu "warichu"})))

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

(deftest vertical-emphasis-svg-emits-static-marks
  (testing "Static SVG draws emphasis marks and skips whitespace characters"
    (let [{:keys [objects shapes]} (setup-text {:width 60 :height 100}
                                               (vertical-strip {:text "強調 あ" :text-emphasis "filled-dot"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"強調 あ" markup))
      ;; 4 base chars: mark, mark, space (whitespace keeps its slot), mark.
      (is (re-find #"•• •" markup))
      (is (re-find #"font-size:10px" markup))
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest horizontal-emphasis-svg-emits-static-marks-above-the-text
  (testing "Static SVG draws horizontal emphasis and excludes punctuation and whitespace"
    (let [{:keys [objects shapes]} (setup-text {:width 120 :height 40}
                                               (horizontal-strip {:text "強調、 あ" :text-emphasis "filled-dot"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"強調、 あ" markup))
      ;; Five Unicode base characters: two marks, punctuation and whitespace
      ;; slots, then one mark.
      (is (re-find #"••  •" markup))
      (is (re-find #"writing-mode:horizontal-tb" markup))
      (is (re-find #"dominant-baseline=\"text-after-edge\"" markup))
      (is (re-find #"lengthAdjust=\"spacing\"" markup))
      (is (re-find #"font-size:10px" markup))
      (is (re-find #"fill:url\(#fill-0-[^)]+-0\)" markup)
          "the emphasis mark references its generated per-strip fill")
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest horizontal-auto-clearance-stacks-emphasis-outside-ruby
  (testing "Static SVG keeps ruby nearest the base and offsets emphasis by another half-em"
    (let [{:keys [objects shapes]} (setup-text {:width 120 :height 40}
                                               (horizontal-strip {:text "漢字"
                                                                  :ruby "かんじ"
                                                                  :text-emphasis "filled-dot"
                                                                  :annotation-clearance "auto"
                                                                  :annotation-has-ruby true}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"かんじ" markup))
      (is (re-find #"••" markup))
      (is (re-find #"y=\"40\"" markup) "ruby occupies the first half-em layer")
      (is (re-find #"y=\"30\"" markup) "emphasis occupies the outer half-em layer"))))

(deftest vertical-warichu-svg-emits-two-sub-columns
  (testing "Static SVG splits a warichu strip into two half-size sub-columns"
    (let [{:keys [objects shapes]} (setup-vertical-warichu-text "割注入り")
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"割注" markup))
      (is (re-find #"入り" markup))
      (is (not (re-find #"割注入り" markup))
          "the base text must be split, not drawn as one strip")
      (is (re-find #"font-size:10px" markup))
      (is (not (re-find #"<foreignObject\b" markup))))))

(deftest vertical-warichu-svg-split-respects-kinsoku
  (testing "The second sub-line must not start with a line-start-prohibited mark"
    (let [{:keys [objects shapes]} (setup-vertical-warichu-text "割り注、と")
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"割り注、" markup)
          "the comma is pulled up into the first sub-line")
      (is (not (re-find #"割り注、と" markup))
          "the strip is still split into two sub-lines"))))

(deftest vertical-warichu-svg-preserves-non-bmp-characters
  (testing "Static SVG never splits a surrogate pair between warichu sub-lines"
    (let [{:keys [objects shapes]} (setup-vertical-warichu-text "割𠀀注😀")
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"割𠀀" markup))
      (is (re-find #"注😀" markup))
      (is (not (re-find #"�" markup))))))

(deftest horizontal-warichu-svg-emits-two-stacked-sub-lines
  (testing "Static SVG splits horizontal warichu into top and bottom half-size lines"
    (let [{:keys [objects shapes]} (setup-text {:width 120 :height 40}
                                               (horizontal-strip {:width 40 :text "割注入り" :warichu "warichu"}))
          markup (svg/generate-markup objects shapes)]
      (is (re-find #"割注" markup))
      (is (re-find #"入り" markup))
      (is (not (re-find #"割注入り" markup)))
      (is (re-find #"writing-mode:horizontal-tb" markup))
      (is (= 2 (count-matches #"dominant-baseline=.?hanging" markup)))
      (is (re-find #"font-size:10px" markup)))))

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
