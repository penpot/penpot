;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.leader-test
  "The queue of the editors that set a page
  (`app.main.data.workspace.tokens.leader`)."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.leader :as leader]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.wasm :as thw]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(t/deftest the-lowest-editor-on-the-page-goes-first
  (let [[a b c] (sort-by str [(uuid/next) (uuid/next) (uuid/next)])
        page    (uuid/next)
        other   (uuid/next)
        presence {a {:can-edit true :page-id page}
                  b {:can-edit true :page-id page}
                  c {:can-edit true :page-id other}}]
    (t/is (= 0 (leader/position presence a page nil)))
    (t/is (= 1 (leader/position presence b page nil)))
    (t/testing "sessions on other pages don't count"
      (t/is (= 0 (leader/position presence c other nil))))
    (t/testing "sessions that can't edit don't count"
      (t/is (= 0 (leader/position (assoc-in presence [a :can-edit] false) b page nil))))
    (t/testing "the author goes first"
      (t/is (= 1 (leader/position presence a page b)))
      (t/is (= 0 (leader/position presence b page b))))
    (t/testing "the author goes first when it sets the page"
      (t/is (= b (leader/author-first presence page b false)) "it sets every page")
      (t/is (= b (leader/author-first presence page b true)) "it is on the page")
      (t/is (nil? (leader/author-first presence other b true)) "a lazy change sets only its page")
      (t/is (nil? (leader/author-first presence page nil true))))
    (t/testing "a session that joins later knows the page of the idle ones"
      (t/is (= 1 (leader/position {a {:can-edit true :page-id page}} c page nil))
            "presence alone tells where `a` is"))
    (t/is (= 0 (leader/wait-ms 0)))
    (t/is (= 3000 (leader/wait-ms 1)))
    (t/is (= 4000 (leader/wait-ms 2)))))
