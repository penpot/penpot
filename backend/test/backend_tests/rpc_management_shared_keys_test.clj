;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-management-shared-keys-test
  "Covers the shared-key scope declared by every management RPC method."
  (:require
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once (t/compose-fixtures
                       (partial th/init-config [:enable-admin-console])
                       th/init-system))

(def ^:private expected-shared-keys
  {"app.rpc.management.exporter" #{:exporter :media-processor}
   "app.rpc.management.files"    #{:nexus}
   "app.rpc.management.jobs"     #{:exporter}
   "app.rpc.management.nitrate"  #{:admin-console}})

(t/deftest every-management-method-declares-its-shared-keys
  (let [methods (get th/*system* :app.rpc/management-methods)
        seen    (into #{} (map (comp :ns first val)) methods)]
    (t/is (seq methods))
    (t/is (= (set (keys expected-shared-keys)) seen)
          "every management namespace must be scanned")
    (doseq [[method-name [mdata _]] methods]
      (let [ns-name  (:ns mdata)
            expected (get expected-shared-keys ns-name)]
        (t/is (some? expected)
              (str "unexpected management namespace for " method-name ": " ns-name))
        (t/is (= expected (::rpc/allowed-shared-keys mdata))
              (str "wrong ::rpc/allowed-shared-keys for " method-name " in " ns-name))))))

(t/deftest management-command-uses-the-key-from-the-request-metadata
  (let [out (th/management-command!
             (with-meta {::th/type :upload-tempfile}
               {:app.http/request {:app.http/auth-key-id :nexus}}))]
    (t/is (= :authorization (th/ex-type (:error out))))
    (t/is (= :shared-key-not-allowed (th/ex-code (:error out))))))

(t/deftest management-command-keeps-an-explicit-nil-key
  (let [out (th/management-command! {::th/type :upload-tempfile
                                     ::rpc/auth-key-id nil})]
    (t/is (= :authorization (th/ex-type (:error out))))
    (t/is (= :shared-key-not-allowed (th/ex-code (:error out))))))
