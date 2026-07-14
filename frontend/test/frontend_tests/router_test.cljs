;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.router-test
  (:require
   [app.main.router :as rt]
   [cljs.test :as t :include-macros true]))

(t/deftest match-context-params-file-link
  ;; Workspace and viewer links only mirror the file-id.
  (let [match {:query-params {:team-id "team-1"
                              :file-id "file-1"
                              :page-id "page-1"}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-file-link-path-params
  ;; Legacy routes carry the ids as path params.
  (let [match {:params {:path {:project-id "project-1"
                               :file-id "file-1"}}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-project-link
  (let [match {:query-params {:team-id "team-1"
                              :project-id "project-1"}}]
    (t/is (= {:team-id "team-1"
              :project-id "project-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-team-link
  (let [match {:query-params {:team-id "team-1"}}]
    (t/is (= {:team-id "team-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-no-context
  (let [match {:query-params {:token "some-token"}}]
    (t/is (nil? (rt/match->context-params match)))))
