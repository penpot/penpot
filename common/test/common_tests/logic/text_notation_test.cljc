;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-notation-test
  "The text notation of `app.common.test-helpers.texts`."
  (:require
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as txt]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest content-is-written-as-the-editors-write-it
  (let [content    (thtx/content [[["Price: "] ["$10" {:weight 700 :color :red}]]])
        paragraph  (first (txt/node-seq txt/is-paragraph-node? content))
        [run1 run2] (txt/node-seq txt/is-text-node? content)]
    (t/testing "every run carries every run style, fills included"
      (t/is (= (assoc thtx/base-style :text "Price: ") run1))
      (t/is (= (assoc thtx/base-style
                      :text "$10"
                      :font-weight "700"
                      :font-variant-id "bold"
                      :fills [{:fill-color "#FF0000" :fill-opacity 1}])
               run2)))
    (t/testing "a paragraph carries its styles and those of its first run"
      (t/is (= (merge thtx/base-style thtx/base-paragraph-style)
               (dissoc paragraph :type :children))))
    (t/testing "the root carries the vertical alignment"
      (t/is (= "top" (:vertical-align content))))))

(t/deftest view-reads-content-back-into-the-notation
  (thi/new-id! :file1)
  (thi/new-id! :body)
  (doseq [text [[[["Hello world"]]]
                [[["Price: "] ["$10" {:weight 700}]]]
                [[["Add to cart" {:family "Roboto" :weight 600 :italic true :size 18 :spacing 2
                                  :case "uppercase" :decoration "underline" :color :blue}]]]
                [{:valign "bottom"}
                 [{:align "center" :direction "rtl" :line-height 1.5} ["Summer " {:weight 700}] ["sale"]]
                 [["Ends Sunday"]]]
                [[["Read the guide" {:typography :body}]]]
                [[["odd" {:font-variant-id "bold"}]]]]]
    (t/is (= text (thtx/normalize text)))))

(t/deftest view-joins-runs-with-equal-styles
  (t/is (= [[["Hello world"]]]
           (thtx/normalize [[["Hello"] [" "] ["world"]]])))
  (t/is (= [[["Hello"]] [[""]]]
           (thtx/normalize [[["Hello"] [""]] [[""]]]))))

(t/deftest edits-of-a-text-in-the-notation
  (thi/new-id! :file1)
  (thi/new-id! :body)
  (let [typed (thtx/apply-typography [[["Read"]]] :body {:family "Roboto" :size 16 :line-height 1.6})]
    (t/is (= [[{:line-height 1.6} ["Read" {:family "Roboto" :size 16 :typography :body}]]]
             typed))
    (t/testing "changing a style the typography sets drops the typography"
      (t/is (= [[{:line-height 1.6} ["Read" {:family "Roboto" :size 20}]]]
               (thtx/restyle typed :size 20)))
      (t/is (= [[{:line-height 2} ["Read" {:family "Roboto" :size 16}]]]
               (thtx/restyle-paragraphs typed :line-height 2))))
    (t/testing "changing another style keeps it"
      (t/is (= [[{:line-height 1.6} ["Read" {:family "Roboto" :size 16 :typography :body :color :red}]]]
               (thtx/restyle typed :color :red)))))
  (t/is (= [[{:align "center"} ["Buy now" {:weight 700}]]
            [{:align "center"} ["Ends Sunday" {:weight 700}]]]
           (thtx/set-letters [[{:align "center"} ["Add " {:weight 700}] ["to cart"]]]
                             "Buy now\nEnds Sunday")))
  (t/is (= [{:valign "bottom"} [["Hello"]]]
           (thtx/set-valign [[["Hello"]]] "bottom"))))

(t/deftest set-text-commits-the-content-through-the-change-path
  (let [file (-> (thf/sample-file :file1)
                 (thtx/add-text-component-with-copy [[["Hello world"]]])
                 (thtx/set-text :copy-child [[["Hello world" {:color :red}]]]))]
    (t/is (= [[["Hello world" {:color :red}]]] (thtx/text-of file :copy-child)))
    (t/is (= #{:content-group :text-content-fills}
             (:touched (ths/get-shape file :copy-child))))))
