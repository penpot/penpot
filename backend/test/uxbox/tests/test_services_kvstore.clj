(ns uxbox.tests.test-services-kvstore
  (:require
   [clojure.spec.alpha :as s]
   [clojure.test :as t]
   [promesa.core :as p]
   [uxbox.db :as db]
   [uxbox.http :as http]
   [uxbox.services.core :as sv]
   [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest test-mutation-upsert-kvstore
  (let [{:keys [id] :as user} @(th/create-user db/pool 1)]
    (let [out (th/try-on! (sv/query {::sv/type :kvstore-entry
                                     :key "foobar"
                                     :user id}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/try-on! (sv/mutation {::sv/type :upsert-kvstore
                                        :user id
                                        :key "foobar"
                                        :value {:some #{:value}}}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/try-on! (sv/query {::sv/type :kvstore-entry
                                     :key "foobar"
                                     :user id}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (= {:some #{:value}} (get-in out [:result :value])))
      (t/is (= "foobar" (get-in out [:result :key]))))

    (let [out (th/try-on! (sv/mutation {::sv/type :delete-kvstore
                                        :user id
                                        :key "foobar"}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))

    (let [out (th/try-on! (sv/query {::sv/type :kvstore-entry
                                     :key "foobar"
                                     :user id}))]
      ;; (th/print-result! out)
      (t/is (nil? (:error out)))
      (t/is (nil? (:result out))))))

