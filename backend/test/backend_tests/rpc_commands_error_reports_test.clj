;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en Espana SL

(ns backend-tests.rpc-commands-error-reports-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private error-reports-read #{"error-reports:read"})

(defn- insert-report!
  "Insert a raw error report row. content is a plain map (will be tjson-encoded)."
  [sys {:keys [id source content created-at]
        :or {created-at (ct/now)}}]
  (th/db-insert! :server-error-report
                 {:id (or id (uuid/next))
                  :source source
                  :created-at created-at
                  :content (db/tjson content)}))

(defn- token-cmd
  "Invoke an RPC method as an access token with the given perms."
  [profile params & {:keys [perms] :or {perms error-reports-read}}]
  (th/command! (assoc params
                      ::rpc/profile-id (:id profile)
                      ::rpc/auth-type :token
                      ::rpc/token-perms perms)))

(defn- session-cmd
  "Invoke an RPC method as a session-authenticated profile."
  [profile params]
  (th/command! (assoc params
                      ::rpc/profile-id (:id profile)
                      ::rpc/auth-type :session)))

;; --- Auth tests

(t/deftest get-error-reports-requires-authentication
  (let [out (th/command! {::th/type :get-error-reports})]
    (t/is (not (th/success? out)))
    (t/is (= :authentication (th/ex-type (:error out))))
    (t/is (= :authentication-required (th/ex-code (:error out))))))

(t/deftest get-error-reports-requires-token-auth
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (session-cmd profile {::th/type :get-error-reports})]
    (t/is (not (th/success? out)))
    (t/is (= :authorization (th/ex-type (:error out))))
    (t/is (= :token-auth-required (th/ex-code (:error out))))))

(t/deftest get-error-reports-requires-perm
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (token-cmd profile {::th/type :get-error-reports} :perms #{})]
    (t/is (not (th/success? out)))
    (t/is (= :authorization (th/ex-type (:error out))))
    (t/is (= :missing-perms (th/ex-code (:error out))))))

(t/deftest get-error-reports-allows-token-with-perm
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (token-cmd profile {::th/type :get-error-reports})]
    (t/is (th/success? out))
    (t/is (vector? (:items (:result out))))
    (t/is (zero? (count (:items (:result out)))))))

;; --- List shape

(t/deftest get-error-reports-returns-summary-shape
  (let [id      (uuid/next)
        profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system*
                    {:id id
                     :source 3
                     :content {:hint "test error"
                               :tenant "devenv"
                               :version "2.20.0-devenv"}})
    (let [out    (token-cmd profile {::th/type :get-error-reports})
          result (:result out)]
      (t/is (th/success? out))
      (t/is (vector? (:items result)))
      (t/is (= 1 (count (:items result))))
      (let [item (first (:items result))]
        (t/is (= id (:id item)))
        (t/is (= "logging" (:source item)))
        (t/is (= "test error" (:hint item)))
        (t/is (= "devenv" (:tenant item)))
        (t/is (= "2.20.0-devenv" (:version item)))
        (t/is (nil? (:content item)))
        (t/is (nil? (:kind item)))))))

;; --- Filters

(t/deftest get-error-reports-filter-by-source
  (let [profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "backend error"}})
    (insert-report! th/*system* {:id (uuid/next) :source 4 :content {:hint "frontend error"}})
    (insert-report! th/*system* {:id (uuid/next) :source 5 :content {:hint "rlimit error"}})
    (let [out (token-cmd profile {::th/type :get-error-reports :source "audit-log"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (= "audit-log" (:source (first (:items (:result out)))))))))

(t/deftest get-error-reports-filter-by-kind
  (let [profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system* {:id (uuid/next) :source 4 :content {:hint "page err" :kind "exception-page"}})
    (insert-report! th/*system* {:id (uuid/next) :source 4 :content {:hint "unhandled" :kind "unhandled-exception"}})
    (let [out (token-cmd profile {::th/type :get-error-reports :kind "exception-page"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (= "exception-page" (:kind (first (:items (:result out)))))))))

(t/deftest get-error-reports-filter-by-tenant
  (let [profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "a" :tenant "tenant-a"}})
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "b" :tenant "tenant-b"}})
    (let [out (token-cmd profile {::th/type :get-error-reports :tenant "tenant-a"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (= "tenant-a" (:tenant (first (:items (:result out)))))))))

(t/deftest get-error-reports-filter-by-hint-ilike
  (let [profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "NullPointerException in render"}})
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "Timeout connecting to DB"}})
    (let [out (token-cmd profile {::th/type :get-error-reports :hint "null"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (str/includes? (:hint (first (:items (:result out)))) "Null")))))

;; --- Kind normalization (old ~:origin vs new ~:kind)

(t/deftest get-error-reports-kind-normalization
  (let [profile (th/create-profile* 1 {:is-active true})]
    (insert-report! th/*system* {:id (uuid/next) :source 4 :content {:hint "old shape" :origin "old-origin"}})
    (insert-report! th/*system* {:id (uuid/next) :source 4 :content {:hint "new shape" :kind "new-kind"}})
    ;; Both should appear in unfiltered list
    (let [out (token-cmd profile {::th/type :get-error-reports})]
      (t/is (th/success? out))
      (t/is (= 2 (count (:items (:result out))))))
    ;; Filtering by kind should match old ~:origin
    (let [out (token-cmd profile {::th/type :get-error-reports :kind "old-origin"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (= "old shape" (:hint (first (:items (:result out)))))))
    ;; Filtering by kind should match new ~:kind
    (let [out (token-cmd profile {::th/type :get-error-reports :kind "new-kind"})]
      (t/is (th/success? out))
      (t/is (= 1 (count (:items (:result out)))))
      (t/is (= "new shape" (:hint (first (:items (:result out)))))))))

;; --- Pagination

(t/deftest get-error-reports-pagination
  (let [profile (th/create-profile* 1 {:is-active true})
        t1 (ct/in-past "10m")
        t2 (ct/in-past "9m")
        t3 (ct/in-past "8m")
        t4 (ct/in-past "7m")]
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "1"} :created-at t1})
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "2"} :created-at t2})
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "3"} :created-at t3})
    (insert-report! th/*system* {:id (uuid/next) :source 3 :content {:hint "4"} :created-at t4})
    ;; Page 1: newest 2
    (let [out (token-cmd profile {::th/type :get-error-reports :limit 2})]
      (t/is (th/success? out))
      (let [{:keys [items next-since next-id]} (:result out)]
        (t/is (= 2 (count items)))
        (t/is (some? next-since))
        (t/is (some? next-id))
        (t/is (= "4" (:hint (first items))))
        (t/is (= "3" (:hint (second items))))
        ;; Page 2: next 2, using since and since-id from page 1
        (let [out2 (token-cmd profile {::th/type :get-error-reports :limit 2 :since next-since :since-id next-id})]
          (t/is (th/success? out2))
          (let [{:keys [items next-since]} (:result out2)]
            (t/is (= 2 (count items)))
            (t/is (nil? next-since))
            (t/is (= "2" (:hint (first items))))
            (t/is (= "1" (:hint (second items))))))))))

(t/deftest get-error-reports-pagination-same-timestamp
  (let [profile (th/create-profile* 1 {:is-active true})
        t (ct/in-past "5m")
        id1 (uuid/next)
        id2 (uuid/next)
        id3 (uuid/next)]
    (insert-report! th/*system* {:id id1 :source 3 :content {:hint "1"} :created-at t})
    (insert-report! th/*system* {:id id2 :source 3 :content {:hint "2"} :created-at t})
    (insert-report! th/*system* {:id id3 :source 3 :content {:hint "3"} :created-at t})
    ;; Page 1: first 2 of the 3 same-timestamp rows
    (let [out (token-cmd profile {::th/type :get-error-reports :limit 2})]
      (t/is (th/success? out))
      (let [{:keys [items next-since next-id]} (:result out)]
        (t/is (= 2 (count items)))
        (t/is (some? next-since))
        (t/is (some? next-id))
        ;; Page 2: should return the remaining row, not skip it
        (let [out2 (token-cmd profile {::th/type :get-error-reports :limit 2 :since next-since :since-id next-id})]
          (t/is (th/success? out2))
          (let [{:keys [items next-since]} (:result out2)]
            (t/is (= 1 (count items)))
            (t/is (nil? next-since))))))))

;; --- Single fetch

(t/deftest get-error-report-requires-token-auth
  (let [profile (th/create-profile* 1 {:is-active true})
        id (uuid/next)]
    (insert-report! th/*system* {:id id :source 3 :content {:hint "single report" :tenant "devenv"}})
    (let [out (session-cmd profile {::th/type :get-error-report :id id})]
      (t/is (not (th/success? out)))
      (t/is (= :authorization (th/ex-type (:error out))))
      (t/is (= :token-auth-required (th/ex-code (:error out)))))))

(t/deftest get-error-report-requires-perm
  (let [profile (th/create-profile* 1 {:is-active true})
        id (uuid/next)]
    (insert-report! th/*system* {:id id :source 3 :content {:hint "single report"}})
    (let [out (token-cmd profile {::th/type :get-error-report :id id} :perms #{})]
      (t/is (not (th/success? out)))
      (t/is (= :authorization (th/ex-type (:error out))))
      (t/is (= :missing-perms (th/ex-code (:error out)))))))

(t/deftest get-error-report-success
  (let [profile (th/create-profile* 1 {:is-active true})
        id (uuid/next)]
    (insert-report! th/*system* {:id id :source 3 :content {:hint "single report" :tenant "devenv"}})
    (let [out (token-cmd profile {::th/type :get-error-report :id id})]
      (t/is (th/success? out))
      (let [result (:result out)]
        (t/is (= id (:id result)))
        (t/is (= "logging" (:source result)))
        (t/is (= "single report" (:hint result)))
        (t/is (= "devenv" (:tenant result)))))))

(t/deftest get-error-report-not-found
  (let [profile (th/create-profile* 1 {:is-active true})
        out     (token-cmd profile {::th/type :get-error-report :id (uuid/next)})]
    (t/is (not (th/success? out)))
    (t/is (= :not-found (th/ex-type (:error out))))
    (t/is (= :report-not-found (th/ex-code (:error out))))))
