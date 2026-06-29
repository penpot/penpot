;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.file-test
  (:require
   [app.plugins.file :as file]
   [cljs.test :as t :include-macros true]))

(t/deftest file-version-created-at-returns-stored-date
  (let [created-at (js/Date.)
        version    (file/file-version-proxy
                    "00000000-0000-0000-0000-000000000000"
                    (random-uuid)
                    {}
                    {:id (random-uuid)
                     :label "Version"
                     :created-at created-at})]
    (t/is (identical? created-at (.-createdAt version)))))
