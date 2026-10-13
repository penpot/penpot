;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.text-editor-output-test
  "What the text editors commit when a copy text is edited, recorded in the
   workspace: the WASM editor (WebGL renderer) and the v1 editor (SVG
   renderer). The DOM editor is only on together with the WASM one, which
   the viewport prefers, so no session runs it. Each fixture is the
   `:content` of one commit, built key for key as the editor wrote it.
   Replaying the commits on a copy gives the touched groups the workspace
   showed, less `:name-group`, which the automatic rename of the frontend
   adds (F27)."
  (:require
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.texts :as thtx]
   [app.common.types.text :as txt]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

;; ---- Fixtures

(def ^:private run-style
  "The run styles both editors write for a text with no styles given."
  {:line-height "1.2" :font-style "normal" :typography-ref-id nil :text-transform "none"
   :font-id "sourcesanspro" :font-size "14" :font-weight "400" :typography-ref-file nil
   :font-variant-id "regular" :text-decoration "none" :letter-spacing "0"
   :fills [{:fill-color "#000000" :fill-opacity 1}] :font-family "sourcesanspro"})

(def ^:private bold {:font-weight "700" :font-variant-id "bold"})

(def ^:private direction {:text-align "left" :text-direction "ltr"})

(defn- run
  [text & [style]]
  (merge run-style style {:text text}))

(defn- paragraph
  "A paragraph as the WASM editor writes it: direction only, the line height
   stays on the runs."
  [& runs]
  (assoc direction :type "paragraph" :children (vec runs)))

(defn- keyed
  "`paragraph` once the v1 editor has opened it."
  [paragraph key]
  (assoc paragraph :key key))

(defn- styled-paragraph
  "A paragraph as the v1 editor writes it when it restyles or adds one: the
   styles of its first run on the paragraph too."
  [key & runs]
  (merge run-style direction {:key key :type "paragraph" :children (vec runs)}))

(defn- text
  [& paragraphs]
  {:type "root"
   :children [{:type "paragraph-set" :children (vec paragraphs)}]
   :vertical-align "top"})

(def ^:private main-text
  "\"Hello world\" as the WASM editor writes a new text: the main of every copy below."
  (text (paragraph (run "Hello world"))))

(def ^:private v1-new-text
  "\"Hello world\" as the v1 editor writes a new text."
  {:type "root"
   :children [{:type "paragraph-set"
               :children [(merge run-style direction
                                 {:key "7aab0" :type "paragraph"
                                  :children [(merge (dissoc run-style :typography-ref-id :typography-ref-file)
                                                    direction
                                                    {:text "Hello world"})]})]}]})

(defn- keystrokes
  "The text after each key typed of `typed`, `f` building it from the
   letters typed so far: the WASM editor commits on every key."
  [typed f]
  (map #(f (subs typed 0 %)) (range 1 (inc (count typed)))))

(def ^:private edits
  "Edit -> editor -> the contents it committed, in order."
  {:type-in-place
   {:wasm (repeat 2 (text (paragraph (run "Hello world!"))))
    :v1   [(text (keyed (paragraph (run "Hello world!")) "2dp2k"))]}

   :select-all-and-type
   {:wasm (concat (keystrokes "Buy now" #(text (paragraph (run %))))
                  [(text (paragraph (run "Buy now")))])
    :v1   [(text (keyed (paragraph (run "Buy now")) "eid7b"))]}

   :enter-and-type
   {:wasm (concat [(text (paragraph (run "Hello world")) (paragraph (run "")))]
                  (keystrokes "Ends Sunday" #(text (paragraph (run "Hello world")) (paragraph (run %))))
                  [(text (paragraph (run "Hello world")) (paragraph (run "Ends Sunday")))])
    :v1   [(text (-> (paragraph (run "Hello world")) (keyed "53n8g") (assoc :fills []))
                 (styled-paragraph "dtt74" (merge (run "Ends Sunday") direction)))]}

   :bold-a-word
   {:wasm (repeat 2 (text (paragraph (run "Hello ") (run "world" bold))))
    :v1   [(text (keyed (paragraph (run "Hello world")) "3qtf9"))
           (text (styled-paragraph "3qtf9" (run "Hello ") (run "world" bold)))]}})

(def ^:private shown
  "Edit -> the text it leaves, in the notation."
  {:type-in-place       [[["Hello world!"]]]
   :select-all-and-type [[["Buy now"]]]
   :enter-and-type      [[["Hello world"]] [["Ends Sunday"]]]
   :bold-a-word         [[["Hello "] ["world" {:weight 700}]]]})

(def ^:private touched
  "Edit -> editor -> the touched groups of the copy text after it."
  {:type-in-place       {:wasm #{:content-group :text-content-text}
                         :v1   #{:content-group :text-content-text}}
   :select-all-and-type {:wasm #{:content-group :text-content-text}
                         :v1   #{:content-group :text-content-text}}
   ;; the WASM editor commits the new paragraph and then each key in it
   :enter-and-type      {:wasm #{:content-group :text-content-text :text-content-structure}
                         :v1   #{:content-group :text-content-structure}}
   ;; the v1 editor also writes the run styles on the paragraph
   :bold-a-word         {:wasm #{:content-group :text-content-structure}
                         :v1   #{:content-group :text-content-attribute :text-content-structure}}})

(defn- copy-text-after
  "The copy text of a main written as `main`, once the copy gets `commits`."
  [main commits]
  (let [file (-> (thf/sample-file :file1)
                 (tho/add-frame-with-text :main-root :main-child ""
                                          :child-params {:content main})
                 (thc/make-component :component1 :main-root)
                 (thc/instantiate-component :component1 :copy-root
                                            {:children-labels [:copy-child]}))]
    (ths/get-shape (reduce #(thtx/set-content %1 :copy-child %2) file commits)
                   :copy-child)))

;; ---- Tests

(t/deftest the-notation-reads-what-the-editors-write
  (t/is (= [[["Hello world"]]] (thtx/view main-text)))
  (t/is (= [[["Hello world"]]] (thtx/view v1-new-text)))
  (doseq [[edit by-editor] edits
          [editor commits] by-editor]
    (t/testing (str edit " in the " editor " editor")
      (t/is (= (shown edit) (thtx/view (last commits)))))))

(t/deftest each-edit-touches-these-groups-of-a-copy
  (doseq [[edit by-editor] edits
          [editor commits] by-editor]
    (t/testing (str edit " in the " editor " editor")
      (let [copy (copy-text-after main-text commits)]
        (t/is (= (shown edit) (thtx/view (:content copy))))
        (t/is (= (get-in touched [edit editor]) (:touched copy)))
        (case edit
          (:type-in-place :select-all-and-type)
          (t/is (not (contains? (:touched copy) :text-content-attribute))
                "typing sets no style override")
          :bold-a-word
          (t/is (not (contains? (:touched copy) :text-content-text))
                "a style change sets no text override")
          nil)))))

(t/deftest a-text-in-one-style-from-either-editor-is-uniform
  ;; WASM: the paragraph has a direction its runs lack; v1: the paragraph has
  ;; typography refs (nil) its runs lack
  (doseq [content [main-text v1-new-text]]
    (tm/known-failure "F79" true false
                      (txt/equal-attrs? content (txt/get-first-paragraph-text-attrs content)))))
