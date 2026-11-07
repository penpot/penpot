;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-zip-test
  (:require
   [app.util.zip :as-alias uz]
   [cljs.test :as t :include-macros true]))

(t/deftest read-as-text-nil-entry-raises-typed-error
  (t/testing "read-as-text guards against nil entry"
    (t/is (thrown-with-msg? js/Error #"nil"
                            (app.util.zip/read-as-text nil)))))
