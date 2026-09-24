;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.demo-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.rpc.commands.profile :as profile]
   [app.tasks.demo-purge :as demo-purge]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest demo-profile-created-without-deleted-at
  (let [profile (th/create-profile* 999 {:is-demo true})]
    (t/is (true? (:is-demo profile)))
    (t/is (nil? (:deleted-at profile)))
    (t/is (some? (:id profile)))))

(t/deftest get-profile-finds-demo-user-without-override
  (let [profile (th/create-profile* 998 {:is-demo true})
        found   (db/run! th/*pool*
                         (fn [{:keys [::db/conn]}]
                           (profile/get-profile conn (:id profile))))]
    (t/is (some? found))
    (t/is (= (:id profile) (:id found)))))

(t/deftest demo-purge-handler-submits-delete-object
  (let [profile   (th/create-profile* 996 {:is-demo true})
        handler   (ig/init-key :app.tasks.demo-purge/handler
                               {::db/pool th/*pool*})
        submitted (atom nil)]
    (with-redefs [wrk/submit! (fn [& {:keys [::wrk/task ::wrk/params]}]
                                (reset! submitted {:task task :params params}))]
      (handler {:props {:profile-id (:id profile)
                        :deleted-at (ct/now)}}))
    (t/is (= :delete-object (:task @submitted)))
    (t/is (= :profile (:object (:params @submitted))))
    (t/is (= (:id profile) (:id (:params @submitted))))
    (t/is (some? (:deleted-at (:params @submitted))))))
