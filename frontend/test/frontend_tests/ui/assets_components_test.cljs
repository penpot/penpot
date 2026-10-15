;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.assets-components-test
  "The check that offers to combine a group of the assets panel as variants."
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.sidebar.assets.components :as assets]
   [cljs.test :as t :include-macros true]))

(def ^:private page-1 (uuid/next))
(def ^:private page-2 (uuid/next))

(defn- component
  [& {:as attrs}]
  (merge {:id (uuid/next) :main-instance-page page-1} attrs))

(t/deftest combine-a-group-reads-its-nested-components
  (t/testing "components only in subgroups"
    (t/is (assets/can-combine-group? {"" [] "sub" {"" [(component) (component)]}})))
  (t/testing "a variant in a subgroup"
    (t/is (not (assets/can-combine-group? {"" [(component)]
                                           "sub" {"" [(component :variant-id (uuid/next))]}}))))
  (t/testing "a subgroup with its main on another page"
    (t/is (not (assets/can-combine-group? {"" [(component)]
                                           "sub" {"" [(component :main-instance-page page-2)]}}))))
  (t/testing "a single component"
    (t/is (not (assets/can-combine-group? {"" [(component)]})))))
