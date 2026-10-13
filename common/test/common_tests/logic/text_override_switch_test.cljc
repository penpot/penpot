;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-override-switch-test
  "What a copy text holds after a variant switch that keeps overrides
   (`mem:common/component-sync-contract`). Each override (the letters, and
   each style) is carried only when the copy has it and the old and new
   variants agree on it; everything else comes from the new variant. Texts
   are written in the notation of `app.common.test-helpers.texts`."
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.test-helpers.variants :as thv]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- switch-text
  "The copy's text when a variant set has the members `old` and `new`, a
   copy of `old` writes `copy-text` and switches to `new`, keeping its
   overrides."
  [old copy-text new]
  (let [file   (-> (thf/sample-file :file1)
                   (thv/add-variant-with-text :v01 :c01 :m01 :c02 :m02 :t01 :t02 "" ""
                                              :text1-params {:content (thtx/content old)}
                                              :text2-params {:content (thtx/content new)})
                   (thc/instantiate-component :c01 :copy :children-labels [:copy-t])
                   (thtx/set-text :copy-t copy-text)
                   (tho/swap-component-in-shape :copy :c02 {:new-shape-label :copy-2
                                                            :keep-touched? true}))
        copy-t (first (:shapes (ths/get-shape file :copy-2)))]
    (thtx/view (:content (ths/get-shape-by-id file copy-t)))))

;; ---- Whole-text copy edits against pairs of variants

(def ^:private small [[["Add to cart"]]])

(def ^:private copy-edits
  "Copy edit -> the overrides it makes, each as the edit that writes it."
  {:letters           {:letters #(thtx/set-letters % "Buy now")}
   :new-paragraph     {:letters #(thtx/set-letters % "Add to cart\nEnds Sunday")}
   :color             {:color #(thtx/restyle % :color :red)}
   :family            {:family #(thtx/restyle % :family "Roboto")}
   :size              {:size #(thtx/restyle % :size 18)}
   :weight            {:weight #(thtx/restyle % :weight 700)}
   :align             {:align #(thtx/restyle-paragraphs % :align "center")}
   :valign            {:valign #(thtx/set-valign % "bottom")}
   :letters-and-color {:letters #(thtx/set-letters % "Buy now")
                       :color   #(thtx/restyle % :color :red)}})

(def ^:private variants
  "Second variant -> [its text, the overrides the two variants disagree on]."
  {:same       [small #{}]
   :letters    [[[["Add to bag"]]] #{:letters}]
   :size       [[[["Add to cart" {:size 24}]]] #{:size}]
   :color      [[[["Add to cart" {:color :blue}]]] #{:color}]
   :new-line   [[[["Add to cart"]] [["Free shipping"]]] #{:letters}]
   :bold-word  [[[["Add to "] ["cart" {:weight 700}]]] #{:weight}]})

(def ^:private known-failures
  "Copy edit -> [bug, how the copy ends, the variants it fails against].
   `:variant`: the copy takes the new variant's text whole; a text in the
   notation: the copy ends with that text."
  {:letters           [["F81" :variant #{:bold-word}]]
   :new-paragraph     [["F79" :variant #{:size :color}]
                       ["F81" :variant #{:bold-word}]]
   :color             [["F77" :variant #{:size}]
                       ["F79" :variant #{:new-line}]
                       ["F81" :variant #{:bold-word}]]
   :family            [["F77" :variant #{:size :color}]
                       ["F79" :variant #{:new-line}]
                       ["F81" :variant #{:bold-word}]]
   :size              [["F77" :variant #{:color}]
                       ["F79" :variant #{:new-line}]
                       ["F81" :variant #{:bold-word}]]
   :weight            [["F77" :variant #{:size :color}]
                       ["F79" :variant #{:new-line}]]
   :letters-and-color [["F77" [[["Buy now" {:size 24}]]] #{:size}]
                       ["F79" :variant #{:new-line}]
                       ["F81" :variant #{:bold-word}]]
   :align             [["F76" :variant (disj (set (keys variants)) :same)]]
   :valign            [["F76" :variant (disj (set (keys variants)) :same)]]})

(defn- known-failure
  [copy-edit variant]
  (some (fn [[bug ends variants]]
          (when (contains? variants variant) [bug ends]))
        (get known-failures copy-edit)))

(t/deftest whole-text-copy-edits-across-a-switch
  (doseq [[copy-title overrides] copy-edits
          [variant-title [new disagree]] variants]
    (t/testing (str "copy " copy-title ", variant " variant-title)
      (let [copy-text (reduce (fn [text f] (f text)) small (vals overrides))
            carried   (remove (comp disagree key) overrides)
            expected  (thtx/normalize (reduce (fn [text [_ f]] (f text)) new carried))
            actual    (switch-text small copy-text new)]
        (if-let [[bug ends] (known-failure copy-title variant-title)]
          (tm/known-failure bug expected
                            (thtx/normalize (if (= ends :variant) new ends))
                            actual)
          (t/is (= expected actual)))))))


;; ---- Cards

(t/deftest relabeled-copy-keeps-its-label-in-a-variant-with-a-bolder-word
  ;; both variants have the same letters, so the label stays; the weights
  ;; come from the new variant, and "Buy now" shares no letters with it, so
  ;; all of it takes the weight of its first letter
  (let [old [[["Add to cart" {:weight 600}]]]
        new [[["Add to " {:weight 600}] ["cart" {:weight 700}]]]]
    (tm/known-failure "F81"
                      [[["Buy now" {:weight 600}]]]
                      new
                      (switch-text old [[["Buy now" {:weight 600}]]] new))))

(t/deftest recolored-copy-keeps-its-color-from-small-to-large
  (let [old [[["Add to cart" {:weight 600}]]]
        new [[["Add to cart" {:weight 600 :size 24}]]]]
    (tm/known-failure "F77"
                      [[["Add to cart" {:weight 600 :size 24 :color :red}]]]
                      new
                      (switch-text old [[["Add to cart" {:weight 600 :color :red}]]] new))))

(t/deftest copy-that-bolds-a-word-keeps-it-from-small-to-large
  (let [old [[["Hello world"]]]
        new [[["Hello world" {:size 24}]]]]
    (tm/known-failure "F77"
                      [[["Hello" {:weight 700 :size 24}] [" world" {:size 24}]]]
                      new
                      (switch-text old [[["Hello" {:weight 700}] [" world"]]] new))))
