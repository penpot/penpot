;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.flags-test
  (:require
   [app.common.flags :as flags]
   [clojure.test :as t]))

(t/deftest audit-log-gc-default-can-be-disabled
  (t/is (contains? (flags/parse flags/default) :audit-log-gc))
  (t/is (not (contains? (flags/parse flags/default [:disable-audit-log-gc])
                        :audit-log-gc))))
