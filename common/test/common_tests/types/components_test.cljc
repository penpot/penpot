;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.types.components-test
  (:require
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.component :as ctk]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(t/deftest variant-main-and-formula-rename
  (let [container {:id (thi/new-id! :container)
                   :is-variant-container true}
        main      {:id (thi/new-id! :main)
                   :variant-id (thi/id :container)
                   :variant-name "Value1"}
        main-bare {:id (thi/new-id! :main-bare)
                   :variant-id (thi/id :container)}
        plain     {:id (thi/new-id! :plain)}]
    (t/is (false? (ctk/is-variant-main? container)))
    (t/is (true? (ctk/is-variant-main? main)))
    (t/is (true? (ctk/is-variant-main? main-bare)))
    (t/is (false? (ctk/is-variant-main? plain)))

    (t/is (true? (ctk/variant-formula-rename? main "Property 1=another 21")))
    (t/is (false? (ctk/variant-formula-rename? main "")))
    (t/is (false? (ctk/variant-formula-rename? main nil)))
    (t/is (false? (ctk/variant-formula-rename? main-bare "Property 1=another 21")))
    (t/is (false? (ctk/variant-formula-rename? container "Property 1=another 21")))
    (t/is (false? (ctk/variant-formula-rename? plain "Button")))))
