;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
