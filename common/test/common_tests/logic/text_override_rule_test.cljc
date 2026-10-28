;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-override-rule-test
  "The override rule for texts (`thtx/override-units`): which overrides a
   copy text must hold against its main."
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as txt]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest absent-and-nil-typography-fields-do-not-author-a-reference
  (let [base (thtx/content [[["Hello world"]]])
        path [:children 0 :children 0 :children 0]
        id   (thi/new-id! :typography)
        file (thi/new-id! :file)
        refs {:typography-ref-id id :typography-ref-file file}]
    (doseq [[left right expected]
            [[{} {:typography-ref-id nil :typography-ref-file nil} #{}]
             [{:typography-ref-id nil :typography-ref-file nil} {} #{}]
             [refs {} #{:typography}]
             [refs (assoc refs :typography-ref-id (thi/new-id! :other-typography)) #{:typography}]
             [refs (assoc refs :typography-ref-file (thi/new-id! :other-file)) #{:typography}]]]
      (let [clear #(dissoc % :typography-ref-id :typography-ref-file)
            main  (update-in base path #(merge (clear %) left))
            copy  (update-in base path #(merge (clear %) right))]
        (t/is (= expected (thtx/override-units main copy)))
        (t/is (= (if (seq expected) #{:text-content-typography} #{})
                 (txt/override-groups main copy)))))))

(defn- overrides
  "The overrides of `copy` against `main` by the reference rule, checked
   against the production one (`txt/override-groups`)."
  [main copy]
  (let [main  (thtx/content main)
        copy  (thtx/content copy)
        units (thtx/override-units main copy)]
    (t/is (= (into #{} (map #(keyword (str "text-content-" (name (get {:letters :text} % %))))) units)
             (txt/override-groups main copy))
          "the production rule agrees")
    units))

(t/deftest shared-letters-come-in-stretches-of-three
  (t/is (= {0 0 1 1 2 2 3 3 4 4 5 5 6 6 7 7 8 8 9 9 10 10}
           (thtx/shared-letters "Hello world" "Hello world!!!")))
  ;; "llo world" is shared; the lone "H" is not
  (t/is (= (zipmap (range 2 11) (range 2 11))
           (thtx/shared-letters "Hello world" "Hallo world")))
  (t/is (= {} (thtx/shared-letters "OK" "Ok")))
  (t/is (= {} (thtx/shared-letters "Price: $10" "Sale"))))

(def ^:private price [[["Price: "] ["$10" {:weight 700}]]])

(t/deftest an-edit-overrides-only-what-it-changes
  (t/testing "typing at the end changes the letters only"
    (t/is (= #{:letters} (overrides [[["Hello world"]]] [[["Hello world!!!"]]]))))
  (t/testing "retyping all of a two-style text changes the letters only"
    (t/is (= #{:letters} (overrides price [[["Sale"]]]))))
  (t/testing "a new line in the style of the letter before it changes the letters only"
    (t/is (= #{:letters}
             (overrides [[["Summer " {:weight 700}] ["sale"]]]
                        [[["Summer " {:weight 700}] ["sale"]] [["Ends Sunday"]]]))))
  (t/testing "bolding a word changes the weight only"
    (t/is (= #{:weight} (overrides [[["Hello world"]]] [[["Hello" {:weight 700}] [" world"]]]))))
  (t/testing "each style is a unit of its own"
    (doseq [[unit copy] {:fills      [[["Hello world" {:color :red}]]]
                         :family     [[["Hello world" {:family "Roboto"}]]]
                         :size       [[["Hello world" {:size 18}]]]
                         :spacing    [[["Hello world" {:spacing 2}]]]
                         :decoration [[["Hello world" {:decoration "underline"}]]]
                         :case       [[["Hello world" {:case "uppercase"}]]]
                         :align      [[{:align "center"} ["Hello world"]]]
                         :direction  [[{:direction "rtl"} ["Hello world"]]]
                         :line-height [[{:line-height 1.5} ["Hello world"]]]
                         :valign     [{:valign "bottom"} [["Hello world"]]]}]
      (t/is (= #{unit} (overrides [[["Hello world"]]] copy)) (str unit))))
  (t/testing "a typography sets each unit it changes, and its reference"
    (thi/new-id! :file1)
    (thi/new-id! :heading)
    (t/is (= #{:family :weight :size :spacing :case :line-height :typography}
             (overrides [[["Hello world"]]]
                        (thtx/apply-typography [[["Hello world"]]] :heading
                                               {:family "Inter" :weight 700 :size 24 :spacing 1
                                                :case "uppercase" :line-height 1.4}))))))

(t/deftest the-expected-copies-hold-exactly-their-overrides
  ;; The copies `text-override-sync-test` expects after a sync, against the
  ;; main they synced from: each must hold the overrides of its own edits
  ;; and nothing else.
  (doseq [[title main copy expected]
          [["bold word, main blue"
            [[["Hello world" {:color :blue}]]]
            [[["Hello" {:weight 700 :color :blue}] [" world" {:color :blue}]]]
            #{:weight}]
           ["bold word, main letters"
            [[["Hello there"]]]
            [[["Hello" {:weight 700}] [" there"]]]
            #{:weight}]
           ["bold word, main replaces it"
            [[["Goodbye world"]]]
            [[["Goodbye world"]]]
            #{}]
           ["bold word, main changes one letter"
            [[["Hallo world"]]]
            [[["Hallo" {:weight 700}] [" world"]]]
            #{:weight}]
           ["bold letter of a short label"
            [[["Ok"]]]
            [[["Ok"]]]
            #{}]
           ["retyped price, main family"
            [[["Price: " {:family "Roboto"}] ["$10" {:family "Roboto" :weight 700}]]]
            [[["Sale" {:family "Roboto"}]]]
            #{:letters}]
           ["new price, main bolds Price"
            [[["Price" {:weight 700}] [": "] ["$10" {:weight 700}]]]
            [[["Price" {:weight 700}] [": "] ["$20" {:weight 700}]]]
            #{:letters}]
           ["added letters, main colors a word"
            [[["Hello "] ["world" {:color :red}]]]
            [[["Hello "] ["world!!!" {:color :red}]]]
            #{:letters}]
           ["new line, main family"
            [[["Summer " {:family "Roboto" :weight 700}] ["sale" {:family "Roboto"}]]]
            [[["Summer " {:family "Roboto" :weight 700}] ["sale" {:family "Roboto"}]]
             [["Ends Sunday" {:family "Roboto"}]]]
            #{:letters}]
           ["across runs, main colors the price"
            [[["Price: "] ["$10" {:weight 700 :color :red}]]]
            [[["Cost: "] ["$10" {:weight 700 :color :red}]]]
            #{:letters}]
           ["worked example, main colors a word"
            [[["Hello "] ["world" {:color :red}]]]
            [[["Hello" {:weight 700}] [" "] ["world!!!" {:color :red}]]]
            #{:letters :weight}]
           ["worked example, main centers"
            [[{:align "center"} ["Hello world"]]]
            [[{:align "center"} ["Hello" {:weight 700}] [" world!!!"]]]
            #{:letters :weight}]
           ["worked example, main light"
            [[["Hello world" {:weight 300}]]]
            [[["Hello" {:weight 700}] [" world!!!"]]]
            #{:letters :weight}]]]
    (t/is (= expected (overrides main copy)) title)))

(t/deftest wrong-sync-results-show-as-overrides
  ;; What today's sync leaves (pinned in `text-override-sync-test`) differs
  ;; from the main where the copy has no override: a scanner finds it.
  (t/testing "F78: the bold lands on the wrong letters"
    (t/is (= #{:letters :weight}
             (overrides [[["Price" {:weight 700}] [": "] ["$10" {:weight 700}]]]
                        [[["Price: " {:weight 700}] ["$20"]]]))))
  (t/testing "F78: the main's red never reaches the shared word"
    (t/is (= #{:letters :fills}
             (overrides [[["Hello "] ["world" {:color :red}]]]
                        [[["Hello world!!!"]]]))))
  (t/testing "F72: a recolored copy keeps the old size"
    (t/is (= #{:fills :size}
             (overrides [[["Add to cart" {:size 18}]]]
                        [[["Add to cart" {:color :red}]]])))))
