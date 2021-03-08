;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2021 UXBOX Labs SL

(ns app.tests.test-svgc
  (:require
   [app.tests.helpers :as th]
   [clojure.java.io :as io]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)

(t/deftest run-svgc-over-sample-file
  (let [svgc (:app.svgparse/svgc th/*system*)
        data (slurp (io/resource "app/tests/_files/sample1.svg"))
        res  (svgc data)]
    (t/is (string? res))
    (t/is (= 2609 (count res)))))
