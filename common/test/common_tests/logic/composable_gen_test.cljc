;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.composable-gen-test
  (:require
   [app.common.schema.test :as smt]
   [app.common.test-helpers.composable.comp.setups :as setup]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.composable.gen :as cg]
   [app.common.test-helpers.composable.oracle :as oracle]
   [app.common.test-helpers.composable.shadow :as shadow]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest oracle-rejects-phantom-and-missing-overrides
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (-> (tm/file situation)
                    (ths/update-shape :copy-child :opacity 0.4))
        authored (shadow/edit {} shape :opacity 0.4)
        missing (ths/update-shape changed :copy-child :touched nil)]
    (t/is (nil? (oracle/check situation {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation changed) {})))
    (t/is (nil? (oracle/check (tm/with-file situation changed) authored)))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation missing) authored)))))

(t/deftest oracle-rejects-lost-propagation-and-overwritten-values
  (let [situation (setup/simple-component-with-labeled-copy)
        shape (tm/resolve-shape situation :copy-child)
        changed (ths/update-shape (tm/file situation) :copy-child :opacity 0.4)
        authored (shadow/edit {} shape :opacity 0.4)
        stale (ths/update-shape changed :copy-child :touched nil)
        lost (ths/update-shape changed :copy-child :opacity 0.7)]
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation stale) {})))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (oracle/check (tm/with-file situation lost) authored)))))

(t/deftest equal-values-do-not-clear-an-authored-group
  (let [result (cg/run {:world [2 0] :ops [[:edit 3 1 1] [:edit 3 1 0]
                                           [:sync 0] [:undo] [:redo]]})]
    (t/is (pos? (get-in result [:executed :edit] 0)))
    (t/is (seq (:trace result)))))

(t/deftest replay-preserves-targets-and-skips
  (let [program {:world [2 1] :ops [[:reset 0] [:edit 3 0 1] [:sync 1]
                                    [:update-main 0] [:detach 0] [:undo] [:redo]]}
        result (cg/run program)]
    (t/is (= (:trace result) (:trace (cg/run program (:trace result)))))
    (t/is (thrown? #?(:clj Exception :cljs js/Error)
                   (cg/run program [{:kind :wrong}])))))

(t/deftest transforms-run-without-a-frontend-and-check-their-inverses
  (let [result (cg/run {:world [2 0] :ops [[:edit 0 0 1]
                                           [:resize 0 0 0] [:sync 0] [:rotate 0 0 0]
                                           [:edit 0 0 2] [:undo] [:redo]]})]
    (t/is (= 1 (get-in result [:executed :resize] 0)))
    (t/is (= 1 (get-in result [:executed :rotate] 0)))))

(t/deftest empty-candidates-are-logged-and-empty-runs-rejected
  (let [result (cg/run {:world [2 0] :ops [[:restore 0] [:edit 0 0 1]]})]
    (t/is (= :skip (get-in result [:trace 0 :status])))
    (t/is (string? (get-in result [:trace 0 :reason]))))
  (t/is (thrown? #?(:clj Exception :cljs js/Error)
                 (cg/run {:world [2 0] :ops [[:restore 0]]}))))

(t/deftest random-compositions
  (let [coverage (atom {})
        failure (atom nil)]
    (smt/check!
     (smt/for [program cg/program-gen]
       (cg/check-program program coverage failure))
     :max-size 30)
    (println "Executed component operations:" (pr-str @coverage))
    (doseq [kind [:edit :reset :update-main :detach :sync :undo :redo]]
      (t/is (pos? (get @coverage kind 0)) (str "No executed " kind)))))
