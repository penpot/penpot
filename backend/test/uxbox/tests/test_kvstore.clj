(ns uxbox.tests.test-kvstore
  (:require [clojure.test :as t]
            [promesa.core :as p]
            [suricatta.core :as sc]
            [buddy.core.codecs :as codecs]
            [uxbox.db :as db]
            [uxbox.util.uuid :as uuid]
            [uxbox.http :as http]
            [uxbox.services.kvstore :as kvs]
            [uxbox.tests.helpers :as th]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)


(t/deftest test-http-kvstore
  (with-open [conn (db/connection)]
    (let [{:keys [id] :as user} (th/create-user conn 1)]

      ;; Not exists at this moment
      (t/is (nil? (kvs/retrieve-kvstore conn {:user id :key "foo" :version -1})))

      ;; Creating new one should work as expected
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/kvstore/foo")
              body {:value "bar" :version -1}
              params {:body body}
              [status data] (th/http-put user uri params)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 200 status))
          (t/is (= (:key data) "foo"))
          (t/is (= (:value data) "bar"))))

      ;; Should exists
      (let [data (kvs/retrieve-kvstore conn {:user id :key "foo"})]
        (t/is (= (:key data) "foo"))
        (t/is (= (:value data) "bar"))

        ;; Overwriting should work
        (th/with-server {:handler @http/app}
          (let [uri (str th/+base-url+ "/api/kvstore/foo")
                body (assoc data :value "baz")
                _ (prn body)
                [status data] (th/http-put user uri {:body body})]
            ;; (println "RESPONSE:" status data)
            (t/is (= 200 status))
            (t/is (= (:key data) "foo"))
            (t/is (= (:value data) "baz")))))

      ;; Should exists and match the overwritten value
      (let [data (kvs/retrieve-kvstore conn {:user id :key "foo"})]
        (t/is (= (:key data) "foo"))
        (t/is (= (:value data) "baz")))

      ;; Delete should work
      (th/with-server {:handler @http/app}
        (let [uri (str th/+base-url+ "/api/kvstore/foo")
              [status data] (th/http-delete user uri)]
          ;; (println "RESPONSE:" status data)
          (t/is (= 204 status))))

      ;; Not exists at this moment
      (t/is (nil? (kvs/retrieve-kvstore conn {:user id :key "foo"})))

      )))

