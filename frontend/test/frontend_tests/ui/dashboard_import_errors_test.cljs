;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.dashboard-import-errors-test
  "The text the import wizard shows under an entry that failed."
  (:require
   [app.main.ui.dashboard.import :as import]
   [app.util.i18n :refer [tr]]
   [cljs.test :as t :include-macros true]))

(t/deftest a-components-v1-file-shows-why-it-cannot-be-imported
  (let [err (tr "dashboard.import.components-v1-error")]
    (t/is (= err (import/import-error-detail err)))))

(t/deftest known-failures-show-their-own-explanation
  (t/is (= (tr "dashboard.import.import-error.check-error")
           (import/import-error-detail "Check error: invalid shape")))
  (t/is (= (tr "dashboard.import.import-error.corrupt-file")
           (import/import-error-detail "The file is corrupt"))))

(t/deftest other-failures-show-the-generic-explanation
  (t/is (= (tr "dashboard.import.import-error.unknown-error")
           (import/import-error-detail "some server hint")))
  (t/is (= (tr "dashboard.import.import-error.unknown-error")
           (import/import-error-detail nil))))
