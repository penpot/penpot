;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.main-test
  "The new `exporter.*` tree loads: requiring `exporter.main` resolves
  its namespace."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.main]))

(t/deftest new-tree-loads
  (t/testing "exporter.main resolves once required"
    (t/is (some? (find-ns 'exporter.main)))))
