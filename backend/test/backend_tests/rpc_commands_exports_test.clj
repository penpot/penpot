;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-commands-exports-test
  (:require
   [app.common.transit :as tr]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ─── Helpers ──────────────────────────────────────────────────────────────

(defn- transit-encode-resource
  "Return a Transit-encoded JSON string for a mock exporter response."
  [resource-map]
  (tr/encode-str resource-map {:type :json}))

(defn- mock-exporter-response
  "Build a mock HTTP response map as returned by app.http.client/req."
  [body status]
  {:status (or status 200)
   :body body})

(defn- call-export-method
  "Invoke an export RPC method directly (bypassing command!) to allow
   metadata on params."
  [method-kw params]
  (let [[_ method-fn] (get-in th/*system* [:app.rpc/methods method-kw])]
    (method-fn params)))

;; ─── Tests ────────────────────────────────────────────────────────────────

(t/deftest export-shapes-wait-true-with-access-token
  (with-mocks
    [http-mock   {:target 'app.http.client/req
                  :return (mock-exporter-response
                           (transit-encode-resource
                            {:id (uuid/next)
                             :name "test.png"
                             :filename "test.png"
                             :uri "http://localhost/assets/test.png"
                             :mtype "image/png"})
                           200)}
     session-create-mock {:target 'app.http.session/create-session-token
                          :return {:id (uuid/next) :token "test-jwt-token"}}
     session-delete-mock {:target 'app.http.session/delete-session-by-id
                          :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          params  {::rpc/profile-id (:id profile)
                   :exports [{:file-id (uuid/next)
                              :page-id (uuid/next)
                              :object-id (uuid/next)
                              :type :png
                              :scale 1
                              :suffix ""
                              :name "test"}]
                   :wait true}
          result  (call-export-method :export-shapes params)]

      (t/is (some? result))
      (t/is (contains? result :uri))
      (t/is (contains? result :id))
      (t/is (contains? result :name))
      (t/is (contains? result :filename))
      (t/is (contains? result :mtype))
      (t/is (= 1 (:call-count @session-create-mock)))
      (t/is (= 1 (:call-count @session-delete-mock)))
      (t/is (= 1 (:call-count @http-mock))))))

(t/deftest export-shapes-wait-false-with-access-token
  (with-mocks
    [http-mock   {:target 'app.http.client/req
                  :return (mock-exporter-response
                           (transit-encode-resource
                            {:id (uuid/next)
                             :name "test.png"
                             :filename "test.png"
                             :mtype "image/png"})
                           200)}
     session-create-mock {:target 'app.http.session/create-session-token
                          :return {:id (uuid/next) :token "test-jwt-token"}}
     session-delete-mock {:target 'app.http.session/delete-session-by-id
                          :return nil}]
    (let [profile (th/create-profile* 2 {:is-active true})
          params  {::rpc/profile-id (:id profile)
                   :exports [{:file-id (uuid/next)
                              :page-id (uuid/next)
                              :object-id (uuid/next)
                              :type :jpeg
                              :scale 2
                              :suffix ""
                              :name "test"}]
                   :wait false}
          result  (call-export-method :export-shapes params)]

      (t/is (some? result))
      (t/is (contains? result :id))
      (t/is (= 1 (:call-count @session-create-mock)))
      ;; IMPORTANT: session must NOT be deleted for wait:false
      (t/is (= 0 (:call-count @session-delete-mock)))
      (t/is (= 1 (:call-count @http-mock))))))

(t/deftest export-shapes-exporter-http-error
  (with-mocks
    [http-mock   {:target 'app.http.client/req
                  :return (mock-exporter-response "Internal Server Error" 500)}
     session-create-mock {:target 'app.http.session/create-session-token
                          :return {:id (uuid/next) :token "test-jwt-token"}}
     session-delete-mock {:target 'app.http.session/delete-session-by-id
                          :return nil}]
    (let [profile (th/create-profile* 3 {:is-active true})
          params  {::rpc/profile-id (:id profile)
                   :exports [{:file-id (uuid/next)
                              :page-id (uuid/next)
                              :object-id (uuid/next)
                              :type :png
                              :scale 1
                              :suffix ""
                              :name "test"}]
                   :wait true}]

      (t/is (thrown? Exception
                     (call-export-method :export-shapes params)))
      (t/is (= 1 (:call-count @http-mock))))))

(t/deftest export-frames-wait-true-with-access-token
  (with-mocks
    [http-mock   {:target 'app.http.client/req
                  :return (mock-exporter-response
                           (transit-encode-resource
                            {:id (uuid/next)
                             :name "frames.pdf"
                             :filename "frames.pdf"
                             :uri "http://localhost/assets/frames.pdf"
                             :mtype "application/pdf"})
                           200)}
     session-create-mock {:target 'app.http.session/create-session-token
                          :return {:id (uuid/next) :token "test-jwt-token"}}
     session-delete-mock {:target 'app.http.session/delete-session-by-id
                          :return nil}]
    (let [profile (th/create-profile* 4 {:is-active true})
          params  {::rpc/profile-id (:id profile)
                   :exports [{:file-id (uuid/next)
                              :page-id (uuid/next)
                              :object-id (uuid/next)
                              :name "Frame 1"}]
                   :wait true}
          result  (call-export-method :export-frames params)]

      (t/is (some? result))
      (t/is (contains? result :uri))
      (t/is (= 1 (:call-count @session-create-mock)))
      (t/is (= 1 (:call-count @session-delete-mock)))
      (t/is (= 1 (:call-count @http-mock))))))

(t/deftest export-shapes-params-validation
  (let [profile (th/create-profile* 5 {:is-active true})]

    ;; Missing required fields
    (t/testing "missing exports"
      (let [result (th/command! {::th/type :export-shapes
                                 ::rpc/profile-id (:id profile)})]
        (t/is (some? (:error result)))))

    (t/testing "empty exports"
      (let [result (th/command! {::th/type :export-shapes
                                 ::rpc/profile-id (:id profile)
                                 :exports []})]
        (t/is (some? (:error result)))))

    (t/testing "invalid export type"
      (let [result (th/command! {::th/type :export-shapes
                                 ::rpc/profile-id (:id profile)
                                 :exports [{:file-id (uuid/next)
                                            :page-id (uuid/next)
                                            :object-id (uuid/next)
                                            :type :invalid
                                            :scale 1
                                            :suffix ""
                                            :name "test"}]})]
        (t/is (some? (:error result)))))))
