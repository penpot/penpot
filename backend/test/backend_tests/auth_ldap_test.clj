;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.auth-ldap-test
  (:require
   [app.auth.ldap :as ldap-auth]
   [clj-ldap.client :as ldap]
   [clojure.test :as t]))

;; --- search-user: filter must be escaped (RED: currently not escaped)

(t/deftest search-user-escapes-email-in-filter
  (t/testing "wildcard * is escaped before building LDAP filter"
    (let [captured-query (atom nil)
          fake-search    (fn [_conn _base-dn params]
                           (reset! captured-query (:filter params))
                           [])]
      (with-redefs [ldap/search fake-search]
        (#'ldap-auth/search-user {:query "(mail=:username)" :sizelimit 1
                                  :attrs-username "uid" :attrs-email "mail"
                                  :attrs-fullname "cn"}
                                 "fry*@planetexpress.com"))
      ;; After fix: * should be escaped as \2a
      (t/is (= "(mail=fry\\2a@planetexpress.com)" @captured-query)
            "filter must have * escaped per RFC 4515"))))

;; --- retrieve-user: email must come from directory, not client (RED)

(t/deftest retrieve-user-uses-directory-email
  (t/testing "returned email is from LDAP directory, not client input"
    (let [fake-search (fn [_conn _base-dn _params]
                        [{:dn "cn=fry,ou=people,dc=planetexpress,dc=com"
                          :mail "fry@planetexpress.com"
                          :cn "Philip J. Fry"
                          :uid "fry"}])
          fake-bind?  (fn [_conn _dn _password] true)]
      (with-redefs [ldap/search fake-search
                    ldap/bind?  fake-bind?]
        (let [cfg     {:query "(mail=:username)" :sizelimit 1
                       :attrs-username "uid" :attrs-email "mail"
                       :attrs-fullname "cn"}
              result  (#'ldap-auth/retrieve-user cfg {:email "fry*@planetexpress.com" :password "fry"})]
          ;; After fix: email should be from directory (fry@planetexpress.com)
          ;; BUG: email is client input (fry*@planetexpress.com)
          (t/is (= "fry@planetexpress.com" (:email result))
                "email must come from LDAP directory attribute, not client input"))))))

;; --- authenticate: full flow with directory email (RED)

(t/deftest authenticate-returns-directory-email
  (t/testing "authenticate returns directory email for profile"
    (let [fake-search (fn [_conn _base-dn _params]
                        [{:dn "cn=amy,ou=people,dc=planetexpress,dc=com"
                          :mail "amy@planetexpress.com"
                          :cn "Amy Wong"
                          :uid "amy"}])
          fake-bind?  (fn [_conn _dn _password] true)]
      (with-redefs [ldap/search fake-search
                    ldap/bind?  fake-bind?
                    ldap/connect (fn [_cfg] (reify java.lang.AutoCloseable (close [_] nil)))]
        (let [cfg    {:query "(mail=:username)" :sizelimit 1
                      :attrs-username "uid" :attrs-email "mail"
                      :attrs-fullname "cn"
                      :bind-dn "cn=admin,dc=planetexpress,dc=com"
                      :bind-password "GoodNewsEveryone"
                      :host "localhost" :port 10389
                      :ssl false :tls false
                      :base-dn "ou=people,dc=planetexpress,dc=com"}
              result (ldap-auth/authenticate cfg {:email "*@planetexpress.com" :password "amy"})]
          ;; After fix: email should be amy@planetexpress.com (directory)
          ;; BUG: email is *@planetexpress.com (client)
          (t/is (= "amy@planetexpress.com" (:email result))
                "authenticate must return directory email, not client-supplied wildcard"))))))
