;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-metrics-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [mockery.core :refer [with-mocks]])
  (:import
   io.prometheus.client.Counter
   io.prometheus.client.Counter$Child))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each (th/serial
                       th/database-reset
                       th/clean-storage))

(defn- make-metrics
  []
  (ig/init-key :app.metrics/metrics
               {:default (select-keys main/default-metrics
                                      [:storage-operations
                                       :storage-dedup])}))

(defn- configure-storage-backend
  [storage]
  (assoc storage ::sto/backend :fs))

(defn- with-metrics
  [storage metrics]
  (assoc storage ::mtx/metrics metrics))

(defn- counter-value
  [metrics id labels]
  (let [collector (mtx/get-collector metrics id)
        instance  (::mdef/instance collector)
        child     (.labels ^Counter instance (into-array String labels))]
    (.get ^Counter$Child child)))

(defn- put!
  [storage content bucket hash]
  (sto/put-object! storage (cond-> {::sto/content (sto/content content)
                                    :bucket bucket
                                    :content-type "text/plain"}
                             (some? hash)
                             (assoc ::sto/deduplicate? true
                                    ::sto/content (sto/wrap-with-hash
                                                   (sto/content content)
                                                   hash)))))

(t/deftest put-emits-op-and-dedup-miss
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))]
    (put! storage "content" "file-media-object" "hash-miss")
    (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-dedup ["miss" "file-media-object"])))))

(t/deftest dedup-hit-reuses-object-without-put
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object1 (put! storage "content" "file-media-object" "hash-hit")
        object2 (put! storage "content" "file-media-object" "hash-hit")]
    (t/is (= (:id object1) (:id object2)))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-dedup ["miss" "file-media-object"])))
    (t/is (= 1.0 (counter-value metrics :storage-dedup ["hit" "file-media-object"])))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["exists" "file-media-object" "fs"])))))

(t/deftest tempfile-skips-dedup
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object1 (put! storage "content" "tempfile" "hash-temp")
        object2 (put! storage "content" "tempfile" "hash-temp")]
    (t/is (not= (:id object1) (:id object2)))
    (t/is (= 2.0 (counter-value metrics :storage-operations ["put" "tempfile" "fs"])))
    (t/is (= 2.0 (counter-value metrics :storage-dedup ["skip" "tempfile"])))))

(t/deftest repair-rewrites-missing-blob
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object1 (put! storage "content" "file-media-object" "hash-repair")]
    (fs/delete (sto/get-object-path storage object1))
    (let [object2 (put! storage "content" "file-media-object" "hash-repair")]
      (t/is (= (:id object1) (:id object2)))
      (t/is (= "content" (slurp (sto/get-object-data storage object2))))
      (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
      (t/is (= 1.0 (counter-value metrics :storage-operations ["repair" "file-media-object" "fs"])))
      (t/is (= 1.0 (counter-value metrics :storage-dedup ["miss" "file-media-object"])))
      (t/is (= 1.0 (counter-value metrics :storage-dedup ["repair" "file-media-object"]))))))

(t/deftest get-touch-and-del-emit-ops
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (put! storage "content" "file-media-object" nil)]
    (t/is (= "content" (slurp (sto/get-object-data storage object))))
    (t/is (bytes? (sto/get-object-bytes storage object)))
    (t/is (true? (sto/touch-object! storage object)))
    (t/is (true? (sto/del-object! storage object)))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["get-data" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["get-bytes" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["touch" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["del" "file-media-object" "fs"])))))

(t/deftest metrics-are-optional
  (let [storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (dissoc ::mtx/metrics))
        object  (sto/put-object! storage {::sto/content (sto/content "content")
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]
    (t/is (sto/object? object))
    (t/is (= "content" (slurp (sto/get-object-data storage object))))))

(t/deftest default-metrics-definitions
  (let [defs main/default-metrics]
    (t/is (= "penpot_storage_operations_total" (::mdef/name (:storage-operations defs))))
    (t/is (= ["op" "bucket" "backend"] (::mdef/labels (:storage-operations defs))))
    (t/is (= "penpot_storage_dedup_total" (::mdef/name (:storage-dedup defs))))
    (t/is (= ["result" "bucket"] (::mdef/labels (:storage-dedup defs))))))

(t/deftest read-labels-object-backend
  ;; An object keeps its own backend; reads must be labeled with it even
  ;; when the storage default points elsewhere (e.g. after a migration).
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (put! storage "content" "file-media-object" nil)
        storage (assoc storage ::sto/backend :s3)]
    (t/is (= "content" (slurp (sto/get-object-data storage object))))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["get-data" "file-media-object" "fs"])))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["get-data" "file-media-object" "s3"])))))

(t/deftest touch-and-del-missing-id-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        id      (uuid/next)]
    (t/is (false? (sto/touch-object! storage id)))
    (t/is (false? (sto/del-object! storage id)))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["touch" "unknown" "fs"])))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["del" "unknown" "fs"])))))

(t/deftest touch-and-del-emit-once
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (put! storage "content" "file-media-object" nil)]
    (t/is (true? (sto/touch-object! storage object)))
    (t/is (true? (sto/del-object! storage object)))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["touch" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["del" "file-media-object" "fs"])))))

(t/deftest expired-object-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (sto/put-object! storage {::sto/content (sto/content "content")
                                          ::sto/expired-at (ct/minus (ct/now) (ct/duration {:hours 1}))
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]
    (t/is (nil? (sto/get-object-data storage object)))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["get-data" "file-media-object" "fs"])))))

(t/deftest failed-probe-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))]
    (put! storage "content" "file-media-object" "hash-probe-fail")
    (with-mocks [_mock {:target 'app.storage.impl/exists-object?
                        :throw (ex-info "boom" {})}]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (put! storage "content" "file-media-object" "hash-probe-fail"))))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["exists" "file-media-object" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-dedup ["miss" "file-media-object"])))
    (t/is (= 0.0 (counter-value metrics :storage-dedup ["hit" "file-media-object"])))
    (t/is (= 0.0 (counter-value metrics :storage-dedup ["repair" "file-media-object"])))))

(t/deftest expired-object-bytes-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (sto/put-object! storage {::sto/content (sto/content "content")
                                          ::sto/expired-at (ct/minus (ct/now) (ct/duration {:hours 1}))
                                          :bucket "file-media-object"
                                          :content-type "text/plain"})]
    (t/is (nil? (sto/get-object-bytes storage object)))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["get-bytes" "file-media-object" "fs"])))))

(t/deftest put-without-bucket-labels-unknown
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (sto/put-object! storage {::sto/content (sto/content "content")
                                          :content-type "text/plain"})]
    (t/is (sto/object? object))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "unknown" "fs"])))
    (t/is (= 1.0 (counter-value metrics :storage-dedup ["skip" "unknown"])))))

(t/deftest failed-write-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))]
    (with-mocks [_mock {:target 'app.storage.impl/put-object
                        :throw (ex-info "boom" {})}]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (put! storage "content" "file-media-object" "hash-write-fail"))))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
    (t/is (= 0.0 (counter-value metrics :storage-dedup ["miss" "file-media-object"])))))

(t/deftest put-survives-metrics-failure
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))]
    (with-mocks [_mock {:target 'app.metrics/run!
                        :throw (ex-info "boom" {})}]
      (let [object (put! storage "content" "file-media-object" "hash-metrics-fail")]
        (t/is (sto/object? object))
        (t/is (= "content" (slurp (sto/get-object-data storage object))))))))

(t/deftest failed-read-emits-nothing
  (let [metrics (make-metrics)
        storage (-> (:app.storage/storage th/*system*)
                    (configure-storage-backend)
                    (with-metrics metrics))
        object  (put! storage "content" "file-media-object" nil)]
    (with-mocks [_mock {:target 'app.storage.impl/get-object-data
                        :throw (ex-info "boom" {})}]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (sto/get-object-data storage object))))
    (t/is (= 1.0 (counter-value metrics :storage-operations ["put" "file-media-object" "fs"])))
    (t/is (= 0.0 (counter-value metrics :storage-operations ["get-data" "file-media-object" "fs"])))))
