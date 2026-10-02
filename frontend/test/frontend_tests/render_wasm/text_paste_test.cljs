;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.text-paste-test
  "Restyling pasted text. Test content already holds the pasted text in the
   style of the span it went into, as after `text-editor-sync-content`."
  (:require
   [app.render-wasm.text-paste :as text-paste]
   [cljs.test :as t :include-macros true]))

(def ^:private regular
  {:font-id "sourcesanspro" :font-variant-id "regular"
   :font-weight "400" :font-style "normal"})

(defn- span
  ([text] (span text {}))
  ([text attrs] (merge regular attrs {:text text})))

(defn- content [& paragraphs]
  {:type "root"
   :children [{:type "paragraph-set"
               :children (mapv (fn [spans] {:type "paragraph" :children spans})
                               paragraphs)}]})

(defn- spans-of [content]
  (mapv :children (-> content :children first :children)))

(defn- fragment-paragraph [& runs]
  {:attrs {} :children (vec runs)})

(defn- run
  ([text] (run text {}))
  ([text attrs] {:text text :attrs attrs}))

(def ^:private bold-variant
  {:font-weight "700" :font-style "normal" :font-variant-id "bold"})

(t/deftest fragment->text
  (t/testing "paragraphs are joined by newlines"
    (t/is (= "ab\n\nc"
             (text-paste/fragment->text
              [(fragment-paragraph (run "a") (run "b"))
               (fragment-paragraph)
               (fragment-paragraph (run "c"))])))))

(t/deftest bold-run-resolves-to-a-font-variant
  (t/testing "a bold run inside a paragraph gets the bold variant of the span's font"
    (let [pasted   (content [(span "Hello big world")])
          fragment [(fragment-paragraph (run "big " {:font-weight "700"}))]
          result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 6})]
      (t/is (= [[(span "Hello ") (span "big " bold-variant) (span "world")]]
               (spans-of result))))))

(t/deftest italic-run-keeps-the-span-weight
  (t/testing "italic over a bold span picks the bold italic variant"
    (let [pasted   (content [(span "x" bold-variant)])
          fragment [(fragment-paragraph (run "x" {:font-style "italic"}))]
          result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 0})]
      (t/is (= [[(span "x" {:font-weight "700" :font-style "italic"
                            :font-variant-id "bolditalic"})]]
               (spans-of result))))))

(t/deftest html-emphasis-replaces-the-caret-emphasis
  (let [no-emphasis {:font-weight "400" :font-style "normal"
                     :text-decoration "none" :text-transform "none"}]
    (t/testing "plain source text pasted into bold, underlined text comes out plain"
      (let [pasted   (content [(span "aXb" (assoc bold-variant :text-decoration "underline"))])
            fragment [(fragment-paragraph (run "X" no-emphasis))]
            result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 1})]
        (t/is (= [[(span "a" (assoc bold-variant :text-decoration "underline"))
                   (span "X" {:text-decoration "none" :text-transform "none"})
                   (span "b" (assoc bold-variant :text-decoration "underline"))]]
                 (spans-of result)))))

    (t/testing "not bold leaves a light weight as it is"
      (let [light    {:font-weight "300" :font-style "normal" :font-variant-id "300"}
            pasted   (content [(span "X" light)])
            fragment [(fragment-paragraph (run "X" no-emphasis))]
            result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 0})]
        (t/is (= [[(span "X" (assoc light :text-decoration "none" :text-transform "none"))]]
                 (spans-of result)))))))

(t/deftest unknown-font-keeps-its-weight
  (t/testing "a font we have no data for cannot resolve a variant, so it stays"
    (let [unknown  (span "x" {:font-id "missing-font"})
          fragment [(fragment-paragraph (run "x" {:font-weight "700"}))]
          result   (text-paste/apply-fragment-styles (content [unknown]) fragment {:para 0 :offset 0})]
      (t/is (= [[unknown]] (spans-of result))))))

(t/deftest typography-link
  (let [linked {:typography-ref-id "typo-id" :typography-ref-file "file-id"}]
    (t/testing "a span changed by the paste leaves its typography"
      (let [fragment [(fragment-paragraph (run "x" {:text-decoration "underline"}))]
            result   (text-paste/apply-fragment-styles
                      (content [(span "x" linked)]) fragment {:para 0 :offset 0})]
        (t/is (= [[(span "x" {:text-decoration "underline"})]]
                 (spans-of result)))))

    (t/testing "a span the paste leaves as it was keeps its typography"
      (let [already  (span "x" (merge bold-variant linked))
            fragment [(fragment-paragraph (run "x" {:font-weight "700"}))]
            result   (text-paste/apply-fragment-styles (content [already]) fragment {:para 0 :offset 0})]
        (t/is (= [[already]] (spans-of result)))))))

(t/deftest ranges-across-paragraphs
  (t/testing "runs after the first paragraph start at offset 0 of their paragraph"
    (let [pasted   (content [(span "abX")] [(span "Ycd")])
          fragment [(fragment-paragraph (run "X" {:font-weight "700"}))
                    (fragment-paragraph (run "Y" {:font-weight "700"}))]
          result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 2})]
      (t/is (= [[(span "ab") (span "X" bold-variant)]
                [(span "Y" bold-variant) (span "cd")]]
               (spans-of result))))))

(t/deftest ranges-count-utf16-units
  (t/testing "an emoji in the fragment moves later runs by its two UTF-16 units"
    (let [pasted   (content [(span "😀b")])
          fragment [(fragment-paragraph (run "😀") (run "b" {:font-weight "700"}))]
          result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 0})]
      (t/is (= [[(span "😀") (span "b" bold-variant)]]
               (spans-of result)))))

  (t/testing "a paste after an emoji starts at the UTF-16 offset WASM reports"
    (let [pasted   (content [(span "😀Xb")])
          fragment [(fragment-paragraph (run "X" {:font-weight "700"}))]
          result   (text-paste/apply-fragment-styles pasted fragment {:para 0 :offset 2})]
      (t/is (= [[(span "😀") (span "X" bold-variant) (span "b")]]
               (spans-of result))))))

(t/deftest fragment->content
  (t/testing "runs become spans over the base style, and empty paragraphs keep one empty span"
    (let [result (text-paste/fragment->content
                  [(fragment-paragraph (run "Hello ") (run "bold" {:font-weight "700"}))
                   (fragment-paragraph)]
                  regular)]
      (t/is (= [[(span "Hello ") (span "bold" bold-variant)]
                [(span "")]]
               (spans-of result)))))

  (t/testing "paragraphs carry the base style"
    (let [result (text-paste/fragment->content [(fragment-paragraph (run "x"))] regular)]
      (t/is (= (assoc regular :type "paragraph")
               (dissoc (-> result :children first :children first) :children))))))
