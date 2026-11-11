;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-theme-test
  (:require
   [app.util.theme :as theme]
   [cljs.test :as t]))

(t/deftest unset-theme-follows-system
  (doseq [system ["light" "dark"]]
    (t/is (= system (theme/resolve-theme nil system)))
    (t/is (= system (theme/resolve-theme theme/default system)))
    (t/is (= system (theme/resolve-theme "system" system)))))

(t/deftest explicit-theme-overrides-system
  (doseq [system ["light" "dark"]]
    (t/is (= "light" (theme/resolve-theme "light" system)))
    (t/is (= "dark" (theme/resolve-theme "dark" system)))
    ;; Older profiles store the dark preference as "default".
    (t/is (= "dark" (theme/resolve-theme "default" system)))))
