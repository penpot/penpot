;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.delete-shared-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.ui.delete-shared :as delete-shared]
   [cljs.test :as t :include-macros true]))

(def ^:private distinct-references
  @#'delete-shared/distinct-references)

(t/deftest a-file-using-several-of-the-libraries-is-listed-once
  (let [file-a (uuid/next)
        file-b (uuid/next)]
    (t/is (= [[file-a "File A"] [file-b "File B"]]
             (distinct-references [[file-a "File A"] [file-b "File B"] [file-a "File A"]])))))
