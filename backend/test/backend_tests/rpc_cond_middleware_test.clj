;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-cond-middleware-test
  (:require
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.cond :as cond]
   [backend-tests.helpers :as th]
   [backend-tests.storage-test :refer [configure-storage-backend]]
   [clojure.test :as t]
   [datoteka.core :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest conditional-requests
  (let [profile (th/create-profile* 1 {:is-active true})
        project (th/create-project* 1 {:team-id (:default-team-id profile)
                                       :profile-id (:id profile)})
        file1   (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        params  {::th/type :get-file
                 :id (:id file1)
                 ::rpc/profile-id (:id profile)
                 :features cfeat/supported-features
                 }]

    (binding [cond/*enabled* true]
      (let [{:keys [error result] :as out} (th/command! params)]
        ;; NOTE: Fails on print because fipps used for pretty print
        ;; tries to load pointers
        ;; (th/print-result! out)
        (t/is (nil? error))
        (t/is (map? result))
        (t/is (contains? (meta result) :app.http/headers))
        (t/is (contains? (meta result) :app.rpc.cond/key))

        (let [etag (-> result meta :app.http/headers (get "etag"))
              {:keys [error result]} (th/command! (assoc params ::cond/key etag))]
          (t/is (nil? error))
          (t/is (fn? result))
          (t/is (= 304 (-> (result nil) :yetti.response/status))))
        ))))

