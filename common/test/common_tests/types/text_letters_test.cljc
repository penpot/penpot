;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.text-letters-test
  "A text seen as letters with a style each (`cttx/content->letters`), and
   the letters two texts share."
  (:require
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as cttx]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- shared
  [a b]
  (cttx/shared-letters (cttx/split-letters a) (cttx/split-letters b)))

(t/deftest letters-of-a-string
  (t/is (= ["a" "b"] (cttx/split-letters "ab")))
  (t/is (= [] (cttx/split-letters "")))
  (t/testing "a letter outside the basic plane stays whole"
    (t/is (= ["a" "😀" "b"] (cttx/split-letters "a😀b")))))

(t/deftest shared-letters-come-in-stretches-of-three
  (t/is (= (zipmap (range 11) (range 11))
           (shared "Hello world" "Hello world!!!")))
  (t/testing "the lone H is too short to count"
    (t/is (= (zipmap (range 2 11) (range 2 11))
             (shared "Hello world" "Hallo world"))))
  (t/is (= {} (shared "OK" "Ok")))
  (t/is (= {} (shared "Price: $10" "Sale")))
  (t/testing "index in the second text -> index in the first"
    (t/is (= {0 1 1 2 2 3} (shared "xabc" "abc")))
    (t/is (= {2 0 3 1 4 2} (shared "abc" "xyabc"))))
  (t/testing "stretches in the middle, with edits around them"
    (t/is (= (merge (zipmap (range 3 10) (range 5 12))
                    (zipmap (range 12 20) (range 12 20)))
             (shared "Hello world, see you" "Bye world, I see you"))))
  (t/testing "line breaks are letters"
    (t/is (= (zipmap (range 11) (range 11))
             (shared "Summer sale" "Summer sale\nEnds Sunday")))))

(t/deftest shared-letters-of-long-texts
  (let [words (str/join " " (map #(str "word" %) (range 2000)))]
    (t/testing "an edit in the middle of a long text"
      (let [edited (str/replace words "word1000 " "WORD ")]
        (t/is (= (- (count edited) 4)
                 (count (shared words edited))))))
    (t/testing "texts too different for the diff share their start and end"
      (let [other (str "start " (apply str (repeat 1500 "x")) (apply str (repeat 1500 "y")) " end")
            mixed (str "start " (apply str (repeat 1500 "y")) (apply str (repeat 1500 "x")) " end")]
        (t/is (= (concat (range 6) (range (- (count other) 4) (count other)))
                 (sort (keys (shared other mixed)))))))))

(t/deftest letters-of-a-content
  (let [letters (cttx/content->letters
                 (thtx/content [[["Hi" {:weight 700}] ["!"]] [[""]] [["Yo"]]]))]
    (t/is (= "Hi!\n\nYo" (cttx/letters-text letters)))
    (t/is (= [0 0 0 1 2 2 2] (map :paragraph (:letters letters))))
    (t/is (= ["700" "700" "400" nil nil "400" "400"]
             (map #(get-in % [:run :font-weight]) (:letters letters))))
    (t/testing "a paragraph with no letters keeps its run"
      (t/is (= [false true false] (map #(contains? % :empty-run) (:paragraphs letters))))
      (t/is (nil? (some :children (:paragraphs letters)))))))

(t/deftest letters-build-the-content-back
  (doseq [text [[[["Hello world"]]]
                [[["Price: "] ["$10" {:weight 700}]]]
                [{:valign "bottom"} [{:align "center"} ["Summer " {:weight 700}] ["sale"]] [[""]] [["Ends Sunday"]]]
                [[["a😀b" {:color :red}]]]]]
    (let [content (thtx/content text)]
      (t/is (= content (cttx/letters->content (cttx/content->letters content)))
            (pr-str text))))
  (t/testing "runs with equal styles join"
    (let [content (thtx/content [[["Hello "] ["world"]]])]
      (t/is (= (thtx/content [[["Hello world"]]])
               (cttx/letters->content (cttx/content->letters content)))))))

(t/deftest the-style-of-a-letter
  (let [letters (cttx/content->letters
                 (thtx/content [{:valign "bottom"}
                                [{:align "center" :line-height 1.5} ["Hi" {:weight 700 :color :red}]]
                                [[""]]]))
        hi      (first (:letters letters))]
    (t/is (= {:font-weight "700" :font-style "normal" :font-variant-id "bold"}
             (cttx/unit-style letters :text-content-weight hi)))
    (t/is (= {:fills [{:fill-color "#FF0000" :fill-opacity 1}]}
             (cttx/unit-style letters :text-content-fills hi)))
    (t/testing "a run without a line height takes its paragraph's"
      (t/is (= {:line-height "1.5"} (cttx/unit-style letters :text-content-line-height hi))))
    (t/testing "a paragraph with no letters gives its run's style"
      (t/is (= {:font-weight "400" :font-style "normal" :font-variant-id "regular"}
               (cttx/unit-style letters :text-content-weight {:paragraph 1}))))
    (t/is (= {:text-align "center"} (cttx/unit-style letters :text-content-align 0)))
    (t/is (= {:vertical-align "bottom"} (cttx/unit-style letters :text-content-valign nil)))))

(t/deftest styles-that-show-the-same
  (t/is (cttx/same-style? {:font-size "14"} {:font-size 14}))
  (t/is (cttx/same-style? {:font-size "14"} {}))
  (t/is (cttx/same-style? {:line-height "1.3333333333333333"} {:line-height "1.33333"}))
  (t/is (cttx/same-style? {:fills []} {}))
  (t/is (cttx/same-style? {:typography-ref-id nil} {}))
  (t/is (cttx/same-style? {:text-align "left"} {}))
  (t/is (cttx/same-style? {:vertical-align "top"} {}))
  (t/is (not (cttx/same-style? {:font-size "14"} {:font-size "18"})))
  (t/is (not (cttx/same-style? {:font-variant-id "700"} {:font-variant-id "700italic"})))
  (t/is (not (cttx/same-style? {:fills [{:fill-color "#000000" :fill-opacity 1}]} {}))))

(t/deftest font-variants-of-the-nodes-that-name-a-font
  (let [content (thtx/content [[["Price: "] ["$10" {:weight 700}]]])
        fixed   (cttx/update-font-variants content #(when (= "bold" (:font-variant-id %)) "700"))]
    (t/is (= [[["Price: "] ["$10" {:weight 700 :font-variant-id "700"}]]] (thtx/view fixed)))
    (t/testing "the paragraph, which holds its first run's styles, keeps them"
      (t/is (= "regular" (get-in fixed [:children 0 :children 0 :font-variant-id]))))))
