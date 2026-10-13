;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-font-variants-test
  "A sync that takes a copy text's family from the copy and its weight from
   the main gives each run a variant of its own family: the closest to its
   weight and style."
  (:require
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.texts :as cthtx]
   [app.common.types.text :as cttx]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.fonts :as fonts]
   [cljs.test :as t :include-macros true]
   [frontend-tests.composable-tests.interpreter :as ftm]))

(def ^:private test-font
  {:id       "test-font"
   :family   "Test"
   :name     "Test"
   :variants [{:id "400" :name "400" :weight "400" :style "normal"}
              {:id "700" :name "700" :weight "700" :style "normal"}]})

(t/use-fixtures :each
  {:before (fn []
             ((:before ftm/fixture))
             (fonts/register! :test-fonts [test-font]))
   :after  (fn []
             (fonts/register! :test-fonts [])
             ((:after ftm/fixture)))})

(def ^:private in-test-font
  {:font-family "Test" :font-id "test-font"})

(defn- run-variants
  [label]
  (->> (cttx/node-seq cttx/is-text-node? (:content (cths/get-shape (ftm/current-file) label)))
       (mapv (juxt :text :font-variant-id))))

(t/deftest fix-font-variants-picks-the-family-closest
  (let [content [[["Price: " (assoc in-test-font :font-variant-id "regular")]
                  ["$10" (assoc in-test-font :weight 700 :font-variant-id "bold")]
                  ["!" {:font-variant-id "bold" :weight 700}]]]
        changes {:redo-changes [{:type :mod-obj
                                 :id (cthi/new-id! :text)
                                 :operations [{:type :set :attr :content :val (cthtx/content content)}
                                              {:type :set :attr :name :val "Price"}]}]}
        fixed   (-> (dwl/fix-font-variants changes) :redo-changes first :operations)]
    (t/is (= [["Price: " "400"] ["$10" "700"] ["!" "bold"]]
             (mapv (juxt :text :font-variant-id)
                   (cttx/node-seq cttx/is-text-node? (:val (first fixed))))))
    (t/is (= {:type :set :attr :name :val "Price"} (second fixed)))))

(t/deftest ^:async a-sync-gives-the-copy-family-variants
  (ftm/install! (tm/make-situation
                 (-> (cthf/sample-file :file1)
                     (cthtx/add-text-component-with-copy [[["Price: "] ["$10" {:weight 700}]]])
                     ;; the copy changes its family, as the editor does
                     (cthtx/set-text :copy-child [[["Price: " (assoc in-test-font :font-variant-id "400")]
                                                   ["$10" (assoc in-test-font :weight 700 :font-variant-id "700")]]]))))
  (t/is (= [["Price: " "400"] ["$10" "700"]] (run-variants :copy-child)))
  ;; the main makes all of it bold: the weight comes from the main, with a
  ;; variant of the main's family ("bold")
  (t/is (= :settled (await (ftm/await-step [(dwsh/update-shapes #{(cthi/id :main-child)}
                                                                #(assoc % :content (cthtx/content [[["Price: " {:weight 700}]
                                                                                                    ["$10" {:weight 700}]]])))]))))
  (t/is (= [["Price: $10" "700"]] (run-variants :copy-child))))
