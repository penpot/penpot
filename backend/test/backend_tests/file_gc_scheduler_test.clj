;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.file-gc-scheduler-test
  (:require
   [app.common.time :as ct]
   [app.jobs :as jobs]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest min-age-validation
  (let [cfg th/*system*]
    (t/testing "int millis, text and absence validate"
      (t/is (uuid? (jobs/submit cfg {::jobs/name   :file-gc-scheduler
                                     ::jobs/params {:min-age 3600000}})))
      (t/is (uuid? (jobs/submit cfg {::jobs/name   :file-gc-scheduler
                                     ::jobs/params {:min-age "1h"}})))
      (t/is (uuid? (jobs/submit cfg {::jobs/name   :file-gc-scheduler
                                     ::jobs/params {}}))))
    (t/testing "garbage fails at submit instead of burning retries"
      (t/is (thrown-with-msg? Exception #"check error"
                              (jobs/submit cfg {::jobs/name   :file-gc-scheduler
                                                ::jobs/params {:min-age {:bogus true}}}))))))

(t/deftest scheduler-submits-one-job-per-eligible-file
  (let [profile (th/create-profile* 1)]
    (doseq [i [1 2 3]]
      (let [file (th/create-file* i {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})]
        ;; Make the files deterministically eligible: pending trim and
        ;; modified long enough ago.
        (th/db-update! :file {:has-media-trimmed false
                              :modified-at (ct/in-past "2h")}
                       {:id (:id file)})))
    (let [result (th/run-task! :file-gc-scheduler {:min-age "1h"})]
      (t/is (= 3 (:processed result)))
      (t/is (= 3 (count (th/db-query :job {:name "file-gc"})))))))
