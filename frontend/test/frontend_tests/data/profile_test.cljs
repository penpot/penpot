;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.profile-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.profile :as dprof]
   [cljs.test :as t :include-macros true]))

(t/deftest profile-update-params-omits-nil-values
  (t/is (= {:fullname "Updated Name"}
           (dprof/profile-update-params {:fullname "Updated Name"
                                         :lang nil
                                         :theme nil}))))

(t/deftest profile-update-params-preserves-present-values
  (t/is (= {:fullname "Updated Name"
            :lang "en"
            :theme "dark"}
           (dprof/profile-update-params {:fullname "Updated Name"
                                         :lang "en"
                                         :theme "dark"}))))

(t/deftest update-profile-accepts-nil-optional-values
  (t/is (some? (dprof/update-profile {:id uuid/zero
                                      :fullname "Updated Name"
                                      :theme nil}))))
