;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-override-sync-test
  "What a copy text holds after its main changes and syncs, when the copy
   has its own letters or styles (`mem:common/component-sync-contract`):
   - letters and line breaks are one override, and each style another;
   - a copy's style override covers the whole text;
   - the main's styles go onto letters that differ from its own by three
     rules: a style with one value across the main's text applies to every
     letter; otherwise letters both texts share (a stretch of at least 3)
     take the main's style; any other letter takes the style of the letter
     before it, or at the start of the text, of the first shared letter
     after it, or with nothing shared, of the main's first letter.
   Texts are written in the notation of `app.common.test-helpers.texts`."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as txt]
   [app.common.types.typographies-list :as cttl]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(def ^:private heading
  {:family "Inter" :weight 700 :size 24 :spacing 1 :case "uppercase" :line-height 1.4})

(def ^:private body
  {:family "Roboto" :weight 300 :size 16 :spacing 0.5 :case "capitalize" :line-height 1.6})

(defn- sample-file
  "A file with the typographies :heading and :body."
  []
  (-> (thf/sample-file :file1)
      (ths/add-sample-typography :heading)
      (ths/add-sample-typography :body)))

(defn- sync-text
  "The copy's text when the main starts as `main`, the copy writes
   `copy-text` (nil for no edit), then the main writes `main-text` and syncs."
  [main copy-text main-text]
  (-> (sample-file)
      (thtx/add-text-component-with-copy main)
      (cond-> (some? copy-text) (thtx/set-text :copy-child copy-text))
      (thtx/set-text :main-child main-text)
      (tho/propagate-component-changes :component1)
      (thtx/text-of :copy-child)))

;; ---- Whole-text edits: every copy edit against every main edit

(def ^:private base [[["Hello world"]]])

(def ^:private copy-edits
  {:letters           #(thtx/set-letters % "Buy now")
   :new-paragraph     #(thtx/set-letters % "Hello world\nEnds Sunday")
   :color             #(thtx/restyle % :color :red)
   :family            #(thtx/restyle % :family "Roboto")
   :size              #(thtx/restyle % :size 18)
   :weight            #(thtx/restyle % :weight 700)
   :spacing           #(thtx/restyle % :spacing 2)
   :decoration        #(thtx/restyle % :decoration "underline")
   :case              #(thtx/restyle % :case "uppercase")
   :line-height       #(thtx/restyle-paragraphs % :line-height 1.5)
   :align             #(thtx/restyle-paragraphs % :align "center")
   :direction         #(thtx/restyle-paragraphs % :direction "rtl")
   :valign            #(thtx/set-valign % "bottom")
   :letters-and-color #(-> % (thtx/set-letters "Buy now") (thtx/restyle :color :red))
   :typography        #(thtx/apply-typography % :heading heading)})

(def ^:private main-edits
  {:letters       #(thtx/set-letters % "Add to bag")
   :new-paragraph #(thtx/set-letters % "Hello world\nSecond line")
   :color         #(thtx/restyle % :color :blue)
   :family        #(thtx/restyle % :family "Inter")
   :size          #(thtx/restyle % :size 24)
   :weight        #(thtx/restyle % :weight 300)
   :spacing       #(thtx/restyle % :spacing 1)
   :decoration    #(thtx/restyle % :decoration "line-through")
   :case          #(thtx/restyle % :case "lowercase")
   :line-height   #(thtx/restyle-paragraphs % :line-height 2)
   :align         #(thtx/restyle-paragraphs % :align "right")
   :direction     #(thtx/restyle-paragraphs % :direction "rtl")
   :valign        #(thtx/set-valign % "center")
   :typography    #(thtx/apply-typography % :body body)})

(def ^:private style-edits
  #{:color :family :size :weight :spacing :decoration :case :line-height
    :align :direction :valign :typography})

(def ^:private known-failures
  "Copy edit -> [bug, how the copy ends, the main edits it fails against].
   `:as-it-was`: the sync leaves the copy as the copy edit left it.
   `:main`: the copy takes the main's text whole."
  (let [one-style (fn [own]
                    [["F72" :as-it-was (disj style-edits own)]
                     ["F79" :as-it-was #{:new-paragraph}]])]
    {:color             (one-style :color)
     :family            (one-style :family)
     :size              (one-style :size)
     :weight            (one-style :weight)
     :spacing           (one-style :spacing)
     :decoration        (one-style :decoration)
     :case              (one-style :case)
     :line-height       (one-style :line-height)
     :typography        [["F72" :as-it-was #{:color :decoration :align :direction :valign}]
                         ["F79" :as-it-was #{:new-paragraph}]]
     :new-paragraph     [["F79" :as-it-was style-edits]]
     :letters-and-color [["F73" :as-it-was (disj style-edits :color)]]
     :align             [["F76" :main (set (keys main-edits))]]
     :direction         [["F76" :main (disj (set (keys main-edits)) :direction)]]
     :valign            [["F76" :main (set (keys main-edits))]]}))

(defn- known-failure
  [copy-edit main-edit]
  (some (fn [[bug ends main-edits]]
          (when (contains? main-edits main-edit) [bug ends]))
        (get known-failures copy-edit)))

(t/deftest whole-text-copy-edits-against-main-edits
  ;; Whole-text edits commute with the rules: the copy keeps what it
  ;; edited and takes the rest from the main, which is its edit applied
  ;; over the main's new text.
  (doseq [[copy-title copy-edit] copy-edits
          [main-title main-edit] main-edits]
    (t/testing (str "copy " copy-title ", main " main-title)
      (let [actual   (sync-text base (copy-edit base) (main-edit base))
            expected (thtx/normalize (copy-edit (main-edit base)))]
        (if-let [[bug ends] (known-failure copy-title main-title)]
          (tm/known-failure bug expected
                            (thtx/normalize (case ends
                                              :as-it-was (copy-edit base)
                                              :main      (main-edit base)))
                            actual)
          (t/is (= expected actual)))))))

;; ---- One style no longer blocks the others

(t/deftest recolored-copy-takes-the-main-font-and-size
  (let [main   [[["Add to cart" {:weight 600}]]]
        copy   [[["Add to cart" {:weight 600 :color :red}]]]
        main'  [[["Add to cart" {:weight 600 :family "Roboto" :size 18}]]]
        actual (sync-text main copy main')]
    (tm/known-failure "F72"
                      [[["Add to cart" {:family "Roboto" :weight 600 :size 18 :color :red}]]]
                      copy
                      actual)))

(t/deftest relabeled-and-recolored-copy-takes-the-main-size
  (let [main   [[["Add to cart" {:weight 600}]]]
        copy   [[["Buy now" {:weight 600 :color :red}]]]
        main'  [[["Add to cart" {:weight 600 :size 18}]]]
        actual (sync-text main copy main')]
    (tm/known-failure "F73"
                      [[["Buy now" {:weight 600 :size 18 :color :red}]]]
                      copy
                      actual)))

(t/deftest centered-copy-stays-centered
  (let [main   [[["Free shipping"]]]
        copy   [[{:align "center"} ["Free shipping"]]]
        main'  [[["Free shipping" {:size 18}]]]
        actual (sync-text main copy main')]
    (tm/known-failure "F76"
                      [[{:align "center"} ["Free shipping" {:size 18}]]]
                      main'
                      actual)))

(t/deftest copy-with-a-font-size-token-takes-the-main-color
  (let [main  [[["Add to cart" {:weight 600}]]]
        file  (-> (sample-file)
                  (thtx/add-text-component-with-copy main))
        page  (thf/current-page file)
        ;; applying a token writes its value into the content and records it
        file  (thf/apply-changes
               file
               (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                           #{(thi/id :copy-child)}
                                           #(assoc %
                                                   :content (thtx/content [[["Add to cart" {:weight 600 :size 16}]]])
                                                   :applied-tokens {:font-size "size.md"})
                                           (:objects page)
                                           {}))
        file' (-> file
                  (thtx/set-text :main-child [[["Add to cart" {:weight 600 :color :blue}]]])
                  (tho/propagate-component-changes :component1))]
    (tm/known-failure "F72"
                      [[["Add to cart" {:weight 600 :size 16 :color :blue}]]]
                      [[["Add to cart" {:weight 600 :size 16}]]]
                      (thtx/text-of file' :copy-child))))

;; ---- Bolding a word

(def ^:private hello-bold [[["Hello" {:weight 700}] [" world"]]])

(t/deftest copy-that-bolds-a-word-takes-the-main-color
  (tm/known-failure "F74"
                    [[["Hello" {:weight 700 :color :blue}] [" world" {:color :blue}]]]
                    hello-bold
                    (sync-text [[["Hello world"]]] hello-bold [[["Hello world" {:color :blue}]]])))

(t/deftest copy-that-bolds-a-word-takes-the-main-letters
  ;; "Hello" is shared, so it stays bold; " there" follows the space
  (tm/known-failure "F74"
                    [[["Hello" {:weight 700}] [" there"]]]
                    hello-bold
                    (sync-text [[["Hello world"]]] hello-bold [[["Hello there"]]])))

(t/deftest copy-that-bolds-a-word-takes-the-main-family
  (tm/known-failure "F74"
                    [[["Hello" {:family "Roboto" :weight 700}] [" world" {:family "Roboto"}]]]
                    hello-bold
                    (sync-text [[["Hello world"]]] hello-bold [[["Hello world" {:family "Roboto"}]]])))

(t/deftest copy-that-bolds-a-word-the-main-replaces-loses-the-bold
  ;; "Goodbye" shares no stretch of 3 letters with "Hello", so it takes the
  ;; style of the first shared letter after it, the regular space
  (tm/known-failure "F74"
                    [[["Goodbye world"]]]
                    hello-bold
                    (sync-text [[["Hello world"]]] hello-bold [[["Goodbye world"]]])))

(t/deftest copy-that-bolds-a-word-the-main-changes-by-one-letter
  ;; "llo world" is shared; "Ha" at the start takes the bold "l" after it
  (tm/known-failure "F74"
                    [[["Hallo" {:weight 700}] [" world"]]]
                    hello-bold
                    (sync-text [[["Hello world"]]] hello-bold [[["Hallo world"]]])))

(t/deftest copy-that-bolds-a-letter-of-a-short-label
  ;; a lone shared letter carries nothing: with no stretch of 3 shared, all
  ;; letters take the style of the copy's first letter
  (let [copy [[["O"] ["K" {:weight 700}]]]]
    (tm/known-failure "F74"
                      [[["Ok"]]]
                      copy
                      (sync-text [[["OK"]]] copy [[["Ok"]]]))))

(t/deftest copy-that-bolds-a-word-keeps-its-weights-when-the-main-is-light
  ;; an override covers the whole text, so "world" stays regular
  (t/is (= hello-bold
           (sync-text [[["Hello world"]]] hello-bold [[["Hello world" {:weight 300}]]]))))

;; ---- Copies with their own letters

(def ^:private price [[["Price: "] ["$10" {:weight 700}]]])

(t/deftest retyped-two-style-copy-takes-the-main-family
  ;; the family has one value across the main, so it applies everywhere;
  ;; "Sale" shares nothing with "Price: $10" and takes the main's first
  ;; letter's weight
  (tm/known-failure "F74"
                    [[["Sale" {:family "Roboto"}]]]
                    [[["Sale"]]]
                    (sync-text price [[["Sale"]]]
                               [[["Price: " {:family "Roboto"}] ["$10" {:family "Roboto" :weight 700}]]])))

(t/deftest copy-that-removes-a-run-takes-the-main-color
  (let [copy [[["$10" {:weight 700}]]]]
    (tm/known-failure "F74"
                      [[["$10" {:weight 700 :color :blue}]]]
                      copy
                      (sync-text price copy
                                 [[["Price: " {:color :blue}] ["$10" {:weight 700 :color :blue}]]]))))

(t/deftest copy-with-new-letters-in-a-run-takes-the-main-family
  (t/is (= [[["Price: " {:family "Roboto"}] ["$20" {:family "Roboto" :weight 700}]]]
           (sync-text price [[["Price: "] ["$20" {:weight 700}]]]
                      [[["Price: " {:family "Roboto"}] ["$10" {:family "Roboto" :weight 700}]]]))))

(t/deftest copy-with-new-letters-across-runs-takes-a-partial-color
  ;; ": $10" is shared, so "$10" takes the red; "Cost" at the start takes
  ;; the black of ":"
  (t/is (= [[["Cost: "] ["$10" {:weight 700 :color :red}]]]
           (sync-text price [[["Cost: "] ["$10" {:weight 700}]]]
                      [[["Price: "] ["$10" {:weight 700 :color :red}]]]))))

(t/deftest copy-with-added-letters-takes-a-partial-color
  ;; "world" is shared, so it turns red; "!!!" follows the "d" before it
  (tm/known-failure "F78"
                    [[["Hello "] ["world!!!" {:color :red}]]]
                    [[["Hello world!!!"]]]
                    (sync-text [[["Hello world"]]] [[["Hello world!!!"]]]
                               [[["Hello "] ["world" {:color :red}]]])))

(t/deftest copy-with-a-new-price-takes-the-main-bold-on-the-right-letters
  ;; "Price: $" is shared; "20" follows the bold "$"
  (tm/known-failure "F78"
                    [[["Price" {:weight 700}] [": "] ["$20" {:weight 700}]]]
                    [[["Price: " {:weight 700}] ["$20"]]]
                    (sync-text price [[["Price: "] ["$20" {:weight 700}]]]
                               [[["Price" {:weight 700}] [": "] ["$10" {:weight 700}]]])))

(t/deftest copy-that-adds-a-line-to-a-two-style-title-takes-the-main-family
  ;; the new line follows the regular "sale" before it, across the break
  (let [main [[["Summer " {:weight 700}] ["sale"]]]
        copy [[["Summer " {:weight 700}] ["sale"]]
              [["Ends Sunday"]]]]
    (tm/known-failure "F74"
                      [[["Summer " {:family "Roboto" :weight 700}] ["sale" {:family "Roboto"}]]
                       [["Ends Sunday" {:family "Roboto"}]]]
                      copy
                      (sync-text main copy
                                 [[["Summer " {:family "Roboto" :weight 700}] ["sale" {:family "Roboto"}]]]))))

(t/deftest copy-with-a-new-line-in-content-without-fills-takes-the-main-color
  ;; `txt/change-text` writes nodes with no :fills, and the merge only
  ;; writes the keys the copy's nodes have
  (let [file  (-> (sample-file)
                  (tho/add-frame-with-text :main-root :main-child "hello world")
                  (thc/make-component :component1 :main-root)
                  (thc/instantiate-component :component1 :copy-root {:children-labels [:copy-child]}))
        page  (thf/current-page file)
        edit  (fn [file label f]
                (thf/apply-changes
                 file
                 (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                             #{(thi/id label)} f (:objects page) {})))
        blue  [{:fill-color "#0000FF" :fill-opacity 1}]
        file' (-> file
                  (edit :copy-child (fn [shape]
                                      (update shape :content txt/change-text "hello world\nsecond line")))
                  (edit :main-child (fn [shape]
                                      (update shape :content
                                              #(txt/transform-nodes txt/is-content-node?
                                                                    (fn [node] (assoc node :fills blue))
                                                                    %))))
                  (tho/propagate-component-changes :component1))]
    (tm/known-failure "F75"
                      [[["hello world" {:color :blue}]] [["second line" {:color :blue}]]]
                      [[["hello world" {:fills nil}]] [["second line" {:fills nil}]]]
                      (thtx/text-of file' :copy-child))))

;; ---- Typographies and library colors

(t/deftest copy-that-resizes-a-typography-text-takes-the-main-color
  ;; the size comes from the copy, so the typography no longer describes
  ;; the run and goes, also when the copy's content kept it
  (let [main  (thtx/apply-typography [[["Read the guide"]]] :body body)
        copy  (thtx/restyle main :size 20)
        kept  (thtx/apply-typography [[["Read the guide"]]] :body (assoc body :size 20))
        main' (thtx/restyle main :color :blue)]
    (t/testing "detached in the panel before resizing"
      (tm/known-failure "F72"
                        (thtx/normalize (thtx/restyle copy :color :blue))
                        (thtx/normalize copy)
                        (sync-text main copy main')))
    (t/testing "resized keeping the typography (plugin API)"
      (tm/known-failure "F72"
                        (thtx/normalize (thtx/restyle copy :color :blue))
                        (thtx/normalize kept)
                        (sync-text main kept main')))))

(t/deftest copy-that-detached-a-typography-takes-a-library-change-through-its-main
  (let [body-attrs {:font-id "sourcesanspro" :font-family "sourcesanspro" :font-variant-id "regular"
                    :font-size "14" :font-weight "400" :font-style "normal"
                    :line-height "1.2" :letter-spacing "0" :text-transform "none"}
        file  (-> (thf/sample-file :file1)
                  (ths/add-sample-typography :body body-attrs)
                  (thtx/add-text-component-with-copy [[["Read the guide" {:typography :body}]]])
                  (thtx/set-text :copy-child [[["Read the guide" {:size 16}]]])
                  (update :data cttl/update-typography (thi/id :body)
                          assoc :font-family "Roboto" :font-id "gfont-roboto"))
        sync  (fn [file asset-type asset-id]
                (thf/apply-changes
                 file
                 (cll/generate-sync-file-changes (pcb/empty-changes) nil asset-type (:id file)
                                                 asset-id (:id file) {(:id file) file} (:id file))))
        file' (-> file
                  (sync :typographies (thi/id :body))
                  (sync :components (thi/id :component1)))]
    (tm/known-failure "F72"
                      [[["Read the guide" {:family "Roboto" :size 16}]]]
                      [[["Read the guide" {:size 16}]]]
                      (thtx/text-of file' :copy-child))))

(t/deftest untouched-copy-with-a-library-color-takes-the-main-size
  ;; the library color sync writes the copy's runs; it is derived, so the
  ;; main's later size change must still arrive
  (let [brand (fn [color] [{:fill-color color :fill-opacity 1
                            :fill-color-ref-id (thi/id :brand)
                            :fill-color-ref-file (thi/id :file1)}])
        sync  (fn [file asset-type asset-id]
                (thf/apply-changes
                 file
                 (cll/generate-sync-file-changes (pcb/empty-changes) nil asset-type (:id file)
                                                 asset-id (:id file) {(:id file) file} (:id file))))
        file  (-> (thf/sample-file :file1)
                  (ths/add-sample-library-color :brand :color "#0000FF"))
        file' (-> file
                  (thtx/add-text-component-with-copy [[["New arrivals" {:weight 600 :fills (brand "#0000FF")}]]])
                  (assoc-in [:data :colors (thi/id :brand) :color] "#7B3FD1")
                  (sync :colors (thi/id :brand))
                  (thtx/set-text :main-child [[["New arrivals" {:weight 600 :size 18 :fills (brand "#7B3FD1")}]]])
                  (sync :components (thi/id :component1)))]
    (t/is (= [[["New arrivals" {:weight 600 :size 18 :fills (brand "#7B3FD1")}]]]
             (thtx/text-of file' :copy-child)))))

;; ---- The worked example: a copy that bolds "Hello" and adds "!!!"

(def ^:private worked-copy [[["Hello" {:weight 700}] [" world!!!"]]])

(def ^:private worked-example
  "Main edit of \"Hello world\" -> what the copy should hold."
  [["colors all of it blue"
    [[["Hello world" {:color :blue}]]]
    [[["Hello" {:weight 700 :color :blue}] [" world!!!" {:color :blue}]]]]

   ["colors \"world\" red"
    [[["Hello "] ["world" {:color :red}]]]
    [[["Hello" {:weight 700}] [" "] ["world!!!" {:color :red}]]]]

   ["changes the size"
    [[["Hello world" {:size 18}]]]
    [[["Hello" {:weight 700 :size 18}] [" world!!!" {:size 18}]]]]

   ["centers it"
    [[{:align "center"} ["Hello world"]]]
    [[{:align "center"} ["Hello" {:weight 700}] [" world!!!"]]]]

   ["changes the letters to \"Hello there\""
    [[["Hello there"]]]
    worked-copy]

   ["changes the letters to \"Goodbye world\""
    [[["Goodbye world"]]]
    worked-copy]

   ["makes \"world\" light"
    [[["Hello "] ["world" {:weight 300}]]]
    worked-copy]

   ["makes all of it light"
    [[["Hello world" {:weight 300}]]]
    worked-copy]

   ["changes the family"
    [[["Hello world" {:family "Roboto"}]]]
    [[["Hello" {:family "Roboto" :weight 700}] [" world!!!" {:family "Roboto"}]]]]

   ["applies a typography"
    (thtx/apply-typography [[["Hello world"]]] :heading heading)
    ;; the weight is the copy's, so the typography goes
    [[{:line-height 1.4}
      ["Hello" {:family "Inter" :weight 700 :size 24 :spacing 1 :case "uppercase"}]
      [" world!!!" {:family "Inter" :size 24 :spacing 1 :case "uppercase"}]]]]])

(t/deftest copy-that-bolds-a-word-and-adds-letters-against-main-edits
  (doseq [[title main' expected] worked-example]
    (t/testing (str "the main " title)
      (let [actual   (sync-text [[["Hello world"]]] worked-copy main')
            expected (thtx/normalize expected)]
        (if (= expected (thtx/normalize worked-copy))
          (t/is (= expected actual))
          (tm/known-failure "F73" expected (thtx/normalize worked-copy) actual))))))

;; ---- The same as today

(t/deftest relabeled-copy-takes-the-main-size
  (t/is (= [[["Buy now" {:weight 600 :size 18}]]]
           (sync-text [[["Add to cart" {:weight 600}]]]
                      [[["Buy now" {:weight 600}]]]
                      [[["Add to cart" {:weight 600 :size 18}]]]))))

(t/deftest relabeled-copy-keeps-its-letters-when-the-main-relabels
  (t/is (= [[["Buy now" {:weight 600}]]]
           (sync-text [[["Add to cart" {:weight 600}]]]
                      [[["Buy now" {:weight 600}]]]
                      [[["Add to bag" {:weight 600}]]]))))
