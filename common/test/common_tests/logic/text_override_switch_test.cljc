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

(def ^:private by-the-rules
  "Copy edit and variant -> what the copy holds, where the copy's edit
   applied over the new variant's text is not what the rules give."
  {;; the new line follows the bold "t" before it
   [:new-paragraph :bold-word] [[["Add to "] ["cart" {:weight 700}]] [["Ends Sunday" {:weight 700}]]]
   ;; the bold keeps its variant id, for the frontend to pick Roboto's
   [:family :bold-word]        [[["Add to " {:family "Roboto"}]
                                 ["cart" {:family "Roboto" :weight 700 :font-variant-id "bold"}]]]})

(t/deftest whole-text-copy-edits-across-a-switch
  (doseq [[copy-title overrides] copy-edits
          [variant-title [new disagree]] variants]
    (t/testing (str "copy " copy-title ", variant " variant-title)
      (let [copy-text (reduce (fn [text f] (f text)) small (vals overrides))
            carried   (remove (comp disagree key) overrides)
            expected  (or (get by-the-rules [copy-title variant-title])
                          (reduce (fn [text [_ f]] (f text)) new carried))]
        (t/is (= (thtx/normalize expected) (switch-text small copy-text new)))))))


;; ---- Cards

(t/deftest relabeled-copy-keeps-its-label-in-a-variant-with-a-bolder-word
  ;; both variants have the same letters, so the label stays; the weights
  ;; come from the new variant, and "Buy now" shares no letters with it, so
  ;; all of it takes the weight of its first letter
  (let [old [[["Add to cart" {:weight 600}]]]
        new [[["Add to " {:weight 600}] ["cart" {:weight 700}]]]]
    (t/is (= [[["Buy now" {:weight 600}]]]
             (switch-text old [[["Buy now" {:weight 600}]]] new)))))

(t/deftest recolored-copy-keeps-its-color-from-small-to-large
  (let [old [[["Add to cart" {:weight 600}]]]
        new [[["Add to cart" {:weight 600 :size 24}]]]]
    (t/is (= [[["Add to cart" {:weight 600 :size 24 :color :red}]]]
             (switch-text old [[["Add to cart" {:weight 600 :color :red}]]] new)))))

(t/deftest copy-that-bolds-a-word-keeps-it-from-small-to-large
  (let [old [[["Hello world"]]]
        new [[["Hello world" {:size 24}]]]]
    (t/is (= [[["Hello" {:weight 700 :size 24}] [" world" {:size 24}]]]
             (switch-text old [[["Hello" {:weight 700}] [" world"]]] new)))))

(t/deftest copy-that-applies-a-typography-keeps-it-and-all-its-styles-across-a-switch
  ;; the typography's family is the variants' own, so only applying it makes
  ;; the family an override
  (let [heading (thtx/apply-typography small :heading {:size 24 :weight 700})
        file    (-> (thf/sample-file :file1)
                    (ths/add-sample-typography :heading)
                    (thv/add-variant-with-text :v01 :c01 :m01 :c02 :m02 :t01 :t02 "" ""
                                               :text1-params {:content (thtx/content small)}
                                               :text2-params {:content (thtx/content small)})
                    (thc/instantiate-component :c01 :copy :children-labels [:copy-t])
                    (thtx/set-text :copy-t heading)
                    (ths/update-shape :copy-t :touched
                                      #{:content-group :text-content-typography :text-content-family
                                        :text-content-weight :text-content-size :text-content-line-height
                                        :text-content-spacing :text-content-case})
                    (tho/swap-component-in-shape :copy :c02 {:new-shape-label :copy-2
                                                             :keep-touched? true}))
        copy-t  (ths/get-shape-by-id file (first (:shapes (ths/get-shape file :copy-2))))]
    (t/is (= (thtx/normalize heading) (thtx/view (:content copy-t))))
    (t/is (= #{:content-group :text-content-typography :text-content-family
               :text-content-weight :text-content-size :text-content-line-height
               :text-content-spacing :text-content-case}
             (:touched copy-t)))))

(t/deftest copy-that-resizes-its-text-holds-no-typography-of-the-new-variant
  ;; both variants show the same size, so the copy's own size stays and
  ;; drops the typography the new variant applies, which then is no override
  (let [large   [[["Add to cart" {:size 24}]]]
        heading (thtx/apply-typography small :heading {:size 24 :weight 700})
        file    (-> (thf/sample-file :file1)
                    (ths/add-sample-typography :heading)
                    (thv/add-variant-with-text :v01 :c01 :m01 :c02 :m02 :t01 :t02 "" ""
                                               :text1-params {:content (thtx/content large)}
                                               :text2-params {:content (thtx/content heading)})
                    (thc/instantiate-component :c01 :copy :children-labels [:copy-t])
                    (thtx/set-text :copy-t (thtx/restyle large :size 18))
                    (ths/update-shape :copy-t :touched #{:content-group :text-content-size})
                    (tho/swap-component-in-shape :copy :c02 {:new-shape-label :copy-2
                                                             :keep-touched? true}))
        copy-t  (ths/get-shape-by-id file (first (:shapes (ths/get-shape file :copy-2))))]
    (t/is (not-any? :typography-ref-id (tree-seq map? :children (:content copy-t))))
    (t/is (= #{:content-group :text-content-size} (:touched copy-t)))))

(t/deftest copy-owning-no-typography-holds-none-of-the-new-variant
  ;; the copy is marked as owning its typography, though neither it nor its
  ;; main applies one; the variants disagree on the typography, so only the
  ;; size carries and drops the typography the new variant applies
  (let [large   [[["Add to cart" {:size 24}]]]
        heading (thtx/apply-typography small :heading {:size 24 :weight 700})
        file    (-> (thf/sample-file :file1)
                    (ths/add-sample-typography :heading)
                    (thv/add-variant-with-text :v01 :c01 :m01 :c02 :m02 :t01 :t02 "" ""
                                               :text1-params {:content (thtx/content large)}
                                               :text2-params {:content (thtx/content heading)})
                    (thc/instantiate-component :c01 :copy :children-labels [:copy-t])
                    (thtx/set-text :copy-t (thtx/restyle large :size 18))
                    (ths/update-shape :copy-t :touched #{:content-group :text-content-size
                                                         :text-content-typography})
                    (tho/swap-component-in-shape :copy :c02 {:new-shape-label :copy-2
                                                             :keep-touched? true}))
        copy-t  (ths/get-shape-by-id file (first (:shapes (ths/get-shape file :copy-2))))]
    (t/is (not-any? :typography-ref-id (tree-seq map? :children (:content copy-t))))
    (t/is (= #{:content-group :text-content-size} (:touched copy-t)))))
