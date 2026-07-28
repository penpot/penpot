;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-zip-test
  (:require
   [app.util.zip :as-alias uz]
   [app.worker.import :as worker.import]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
   [promesa.core :as p]))

(t/deftest read-as-text-nil-entry-raises-typed-error
  (t/testing "read-as-text guards against nil entry"
    (t/is (thrown-with-msg? js/Error #"nil"
                            (app.util.zip/read-as-text nil)))))

(t/deftest read-zip-manifest-missing-throws-validation-error
  (t/async
    done
    (t/testing "read-zip-manifest rejects ZIPs without manifest.json"
      (mock/with-mocks
        {app.util.zip/get-entry (mock/stub (fn [_ _] (p/resolved nil)))}
        (fn [done']
          (->> (worker.import/read-zip-manifest #js {})
               (rx/subs!
                (fn [_]
                  (t/is false "expected validation error to be thrown")
                  (done'))
                (fn [err]
                  (let [data (ex-data err)]
                    (t/is (= :invalid-penpot-file (:code data))
                          "missing manifest.json raises typed :invalid-penpot-file error")
                    (t/is (string? (:hint data))
                          "missing manifest.json error carries a :hint")
                    (t/is (re-find #"manifest\.json" (:hint data))
                          "missing manifest.json :hint mentions manifest.json")
                    (t/is (nil? (re-find #"getData" (:hint data)))
                          "missing manifest.json :hint does not leak the raw TypeError text")
                    (done'))))))
        done))))