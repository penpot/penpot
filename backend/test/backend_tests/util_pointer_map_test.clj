;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.util-pointer-map-test
  (:require
   [app.common.fressian :as fres]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.util.pointer-map :as pmap]
   [clojure.test :as t]))

(t/deftest basic-operations
  (t/testing "assoc"
    (let [obj (-> (pmap/create) (assoc :a 1))]
      (t/is (contains? obj :a))
      (t/is (not (contains? obj :b)))
      (t/is (= 1 (count obj)))))

  (t/testing "dissoc"
    (let [obj (pmap/wrap {:a 1 :b 2})]
      (t/is (contains? obj :a))
      (t/is (contains? obj :b))
      (let [obj (dissoc obj :a)]
        (t/is (not (contains? obj :a)))
        (t/is (contains? obj :b)))))

  (t/testing "seq"
    (let [obj (pmap/wrap {:a 1 :b 2})
          s1  (first obj)]
      (t/is (= (key s1) :a))
      (t/is (= (val s1) 1))))

  (t/testing "cons & count"
    (let [obj (into (pmap/create) [[:a 1]])]
      (t/is (contains? obj :a))
      (t/is (= 1 (count obj)))
      (t/is (pmap/pointer-map? obj))))

  (t/testing "wrap"
    (let [obj1 (pmap/wrap {})
          tmp  (pmap/create)
          obj2 (pmap/wrap tmp)]
      (t/is (pmap/pointer-map? obj1))
      (t/is (pmap/pointer-map? obj2))
      (t/is (identical? tmp obj2))
      (t/is (= 0 (count obj1)))
      (t/is (= 0 (count obj2))))))


(t/deftest internal-tracking
  (t/testing "simple tracking"
    (binding [pmap/*tracked* (atom {})]
      (let [obj (pmap/create)]
        (t/is (pmap/modified? obj))
        (t/is (uuid? (pmap/get-id obj)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj)))
        (t/is (= 1 (count @pmap/*tracked*))))))

  (t/testing "tracking modifying modified"
    (binding [pmap/*tracked* (atom {})]
      (let [obj1 (pmap/create)
            obj2 (assoc obj1 :a 1)]
        (t/is (pmap/modified? obj1))
        (t/is (not= obj1 obj2))
        (t/is (= 0 (count obj1)))
        (t/is (= 1 (count obj2)))
        (t/is (uuid? (pmap/get-id obj1)))
        (t/is (uuid? (pmap/get-id obj2)))
        (t/is (= (pmap/get-id obj2)
                 (pmap/get-id obj1)))
        (t/is (= (hash (pmap/get-id obj2))
                 (hash (pmap/get-id obj1))))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))
        (t/is (= 1 (count @pmap/*tracked*))))))

  (t/testing "tracking modifying not modified"
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (constantly {})]
      (let [obj1 (pmap/create uuid/zero {:file-id uuid/zero})
            obj2 (assoc obj1 :a 1)]
        (t/is (pmap/modified? obj2))
        (t/is (not (pmap/modified? obj1)))
        (t/is (not= obj1 obj2))
        (t/is (= 0 (count obj1)))
        (t/is (= 1 (count obj2)))
        (t/is (uuid? (pmap/get-id obj1)))
        (t/is (uuid? (pmap/get-id obj2)))
        (t/is (not= (pmap/get-id obj2)
                    (pmap/get-id obj1)))
        (t/is (not= (hash (pmap/get-id obj2))
                    (hash (pmap/get-id obj1))))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj1)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))
        (t/is (= 2 (count @pmap/*tracked*))))))

  (t/testing "loading"
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (constantly {:a 1})]
      (let [obj1 (pmap/create uuid/zero {:file-id uuid/zero})]
        (t/is (not (pmap/modified? obj1)))
        (t/is (= 1 (count obj1)))
        (t/is (= uuid/zero (pmap/get-id obj1)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj1)))
        (t/is (= 1 (count @pmap/*tracked*)))
        (t/is (contains? obj1 :a))
        (t/is (not (contains? obj1 :b)))
        (t/is (= 1 (get obj1 :a)))
        (t/is (= nil (get obj1 :b)))
        (t/is (= ::empty (get obj1 :b ::empty))))))

  (t/testing "with-meta"
    (binding [pmap/*tracked* (atom {})
              pmap/*load-fn* (constantly {})]
      (let [obj1 (pmap/create uuid/zero {:file-id uuid/zero})
            obj2 (with-meta obj1 {:foo :bar})
            obj3 (with-meta obj1 {:file-id uuid/zero})]
        ;; A metadata change is a new pointer: fresh id and tracked, so
        ;; the fragment row is never rewritten under the same id.
        (t/is (pmap/modified? obj2))
        (t/is (not= (pmap/get-id obj1) (pmap/get-id obj2)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))
        ;; `with-meta` replaces the metadata, so the file id is gone: that
        ;; is the documented sharp edge of the protocol.
        (t/is (nil? (:file-id (meta obj2))))
        ;; The same metadata keeps the pointer untouched.
        (t/is (not (pmap/modified? obj3)))
        (t/is (= (pmap/get-id obj1) (pmap/get-id obj3))))))

  (t/testing "vary-meta keeps the file id"
    (binding [pmap/*tracked* (atom {})]
      (let [obj1 (pmap/create uuid/zero {:file-id uuid/zero})
            obj2 (vary-meta obj1 assoc :foo :bar)]
        (t/is (= uuid/zero (:file-id (meta obj2))))
        (t/is (= :bar (:foo (meta obj2))))
        (t/is (pmap/modified? obj2))
        (t/is (not= (pmap/get-id obj1) (pmap/get-id obj2)))
        (t/is (contains? @pmap/*tracked* (pmap/get-id obj2)))))))

(t/deftest file-id-metadata
  (t/testing "wrap copies the bound metadata"
    (binding [pmap/*metadata* {:file-id uuid/zero}]
      (let [obj (pmap/wrap {})]
        (t/is (= uuid/zero (:file-id (meta obj))))
        (t/is (some? (:created-at (meta obj)))))))

  (t/testing "the loader receives the file id of the pointer"
    (let [calls (atom [])]
      (binding [pmap/*load-fn* (fn [file-id id]
                                 (swap! calls conj [file-id id])
                                 {:a 1})
                pmap/*tracked* (atom {})]
        (let [obj (pmap/create uuid/zero {:file-id uuid/zero})]
          (t/is (= 1 (count obj)))
          (t/is (= [[uuid/zero uuid/zero]] @calls))))))

  (t/testing "a pointer without file id fails before asking the loader"
    (binding [pmap/*load-fn* (constantly {})]
      (let [obj (pmap/create uuid/zero {})]
        (try
          (deref obj)
          (t/is false "expected a pointer-without-file-id error")
          (catch clojure.lang.ExceptionInfo cause
            (t/is (= :pointer-without-file-id (:code (ex-data cause))))))))))

(t/deftest serialization-roundtrip
  ;; The persistence handlers carry the id and the metadata by hand, so
  ;; the `:file-id` has to survive both encodings for a pointer to be
  ;; resolvable after a read. `:created-at` is stored with millisecond
  ;; precision by the handlers, so only its presence is asserted.
  (let [obj (binding [pmap/*metadata* {:file-id uuid/zero}
                      pmap/*tracked* (atom {})]
              (pmap/wrap {}))]

    (t/testing "fressian keeps the id and the metadata"
      (let [obj' (fres/decode (fres/encode obj))]
        (t/is (pmap/pointer-map? obj'))
        (t/is (= (pmap/get-id obj) (pmap/get-id obj')))
        (t/is (= uuid/zero (:file-id (meta obj'))))
        (t/is (some? (:created-at (meta obj'))))))

    (t/testing "transit keeps the id and the metadata"
      (let [ptr (transit/decode (transit/encode obj))]
        (t/is (transit/pointer? ptr))
        (t/is (= (pmap/get-id obj) @ptr))
        (t/is (= uuid/zero (:file-id (meta ptr))))
        (t/is (some? (:created-at (meta ptr))))))))

