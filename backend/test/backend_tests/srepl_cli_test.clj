;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.srepl-cli-test
  (:require
   [app.srepl.cli :as cli]
   [app.system :as system]
   [clojure.test :as t]))

(t/deftest delete-profiles-in-bulk-command
  (let [received (atom nil)]
    (with-redefs [system/system       {:service :test}
                  cli/delete-profiles! (fn [system emails]
                                         (reset! received [system emails])
                                         {:deleted 2 :total 2})]
      (t/is (= {:deleted 2 :total 2}
               (cli/exec {:cmd "delete-profiles-in-bulk"
                          :params {:emails ["one@example.com"
                                            "two@example.com"]}})))
      (t/is (= [{:service :test}
                ["one@example.com" "two@example.com"]]
               @received)))))

(t/deftest delete-profiles-in-bulk-requires-emails
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"non-empty email list"
         (cli/exec {:cmd "delete-profiles-in-bulk"
                    :params {:emails []}}))))
