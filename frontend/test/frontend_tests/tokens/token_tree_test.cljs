;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.token-tree-test
  (:require
   [app.common.path-names :as cpn]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.management.token-tree :as wtt]
   [cljs.test :as t :include-macros true]))

(def ^:private sort-nodes @#'wtt/sort-nodes)

(defn- tree
  [& names]
  (cpn/build-tree-root
   (map #(ctob/make-token :name % :type :spacing :value "4") names)
   ctob/token-separator))

(defn- sorted-names
  [nodes]
  (map :name (sort-nodes nodes)))

(t/deftest sort-nodes-test
  (t/testing "tokens come before groups"
    (t/is (= ["zeta" "alpha" "beta"]
             (sorted-names (tree "alpha.sm" "zeta" "beta.md")))))

  (t/testing "numbers in names sort by value"
    (t/is (= ["size2" "size10" "group2" "group10"]
             (sorted-names (tree "size10" "group10.sm" "size2" "group2.sm")))))

  (t/testing "a token that also has tokens below it sorts with the groups"
    (t/is (= ["md" "space"]
             (sorted-names (tree "space.sm" "md" "space"))))))
