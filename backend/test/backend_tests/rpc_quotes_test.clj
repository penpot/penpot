;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-quotes-test
  (:require
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.cond :as cond]
   [app.rpc.quotes :as-alias quotes]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.core :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest teams-per-profile-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-teams-per-profile 2})}]

    (let [profile-1 (th/create-profile* 1)
          profile-2 (th/create-profile* 2)
          data      {::th/type :create-team
                     ::rpc/profile-id (:id profile-1)}
          check-ok! (fn [n]
                      (let [data (assoc data :name (str "team" n))
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))
          check-ko! (fn [n]
                      (let [data (assoc data :name (str "team" n))
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (not (th/success? out)))
                        (let [error (:error out)]
                          (t/is (= :restriction (th/ex-type error)))
                          (t/is (= :max-quote-reached (th/ex-code error)))
                          (t/is (= "teams-per-profile" (:target (ex-data error)))))))]

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-2)
                      :target "teams-per-profile"
                      :quote 100})

      (check-ok! 1)
      (check-ko! 2)

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-1)
                      :target "teams-per-profile"
                      :quote 3})

      (check-ok! 2)
      (check-ko! 3))))

(t/deftest projects-per-team-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-projects-per-team 2})}]

    (let [profile-1  (th/create-profile* 1)
          profile-2  (th/create-profile* 2)
          team-id    (:default-team-id profile-1)
          data       {::th/type :create-project
                      :profile-id (:id profile-1)
                      :team-id team-id}

          check-ok! (fn [name]
                      (let [data (assoc data :name (str "project" name))
                            out  (th/mutation! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))

          check-ko! (fn [name]
                      ;; create second project
                      (let [data (assoc data :name (str "project" name))
                            out  (th/mutation! data)]
                        ;; (th/print-result! out)
                        (t/is (not (th/success? out)))
                        (let [error (:error out)]
                          (t/is (= :restriction (th/ex-type error)))
                          (t/is (= :max-quote-reached (th/ex-code error)))
                          (t/is (= "projects-per-team" (:target (ex-data error)))))))]

      (check-ok! 1)
      (check-ko! 2)

      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :target "projects-per-team"
                      :quote 3})

      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :profile-id (:id profile-2)
                      :target "projects-per-team"
                      :quote 10})

      (check-ok! 2)
      (check-ko! 3)

      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :profile-id (:id profile-1)
                      :target "projects-per-team"
                      :quote 4})

      (check-ok! 3)
      (check-ko! 4)

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-1)
                      :target "projects-per-team"
                      :quote 5})

      (check-ok! 4)
      (check-ko! 5)

      )))

(t/deftest invitations-per-team-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-invitations-per-team 2})}]
    (let [profile-1 (th/create-profile* 1)
          profile-2 (th/create-profile* 2)
          data      {::th/type :create-team-invitations
                     ::rpc/profile-id (:id profile-1)
                     :team-id (:default-team-id profile-1)
                     :role :editor}

          check-ok! (fn [n]
                      (let [data (assoc data :emails [(str "foo" n "@example.net")])
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))
          check-ko! (fn [n]
                      (let [data (assoc data :emails [(str "foo" n "@example.net")])
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (not (th/success? out)))
                        (let [error (:error out)]
                          (t/is (= :restriction (th/ex-type error)))
                          (t/is (= :max-quote-reached (th/ex-code error)))
                          (t/is (= "invitations-per-team" (:target (ex-data error)))))))]

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-2)
                      :target "invitations-per-team"
                      :quote 100})

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-2)
                      :target "invitations-per-team"
                      :quote 100})

      (check-ok! 1)
      (check-ok! 2)
      (check-ko! 3)

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-1)
                      :target "invitations-per-team"
                      :quote 3})

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-1)
                      :profile-id (:id profile-2)
                      :target "invitations-per-team"
                      :quote 100})

      (check-ok! 3)
      (check-ko! 4)

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-1)
                      :profile-id (:id profile-1)
                      :target "invitations-per-team"
                      :quote 4})

      (check-ok! 4)
      (check-ko! 5)

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-1)
                      :target "invitations-per-team"
                      :quote 5})

      (check-ok! 5)
      (check-ko! 6))))


(t/deftest profiles-per-team-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-profiles-per-team 3})}]
    (let [profile-1 (th/create-profile* 1)
          profile-2 (th/create-profile* 2)
          data      {::th/type :create-team-invitations
                     ::rpc/profile-id (:id profile-1)
                     :team-id (:default-team-id profile-1)
                     :role :editor}

          check-ok! (fn [n]
                      (let [data (assoc data :emails [(str "foo" n "@example.net")])
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))
          check-ko! (fn [n]
                      (let [data (assoc data :emails [(str "foo" n "@example.net")])
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (not (th/success? out)))
                        (let [error (:error out)]
                          (t/is (= :restriction (th/ex-type error)))
                          (t/is (= :max-quote-reached (th/ex-code error)))
                          (t/is (= "profiles-per-team" (:target (ex-data error)))))))]

      (th/create-team-role* {:team-id (:default-team-id profile-1)
                             :profile-id (:id profile-2)
                             :role :admin})

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-2)
                      :target "profiles-per-team"
                      :quote 100})

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-2)
                      :target "profiles-per-team"
                      :quote 100})


      (check-ok! 1)
      (check-ko! 2)

      (th/db-insert! :usage-quote
                     {:team-id (:default-team-id profile-1)
                      :target "profiles-per-team"
                      :quote 4})

      (check-ok! 2)
      (check-ko! 3))))



(t/deftest files-per-project-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-files-per-project 1})}]

    (let [profile-1 (th/create-profile* 1)
          profile-2 (th/create-profile* 2)
          project-1 (th/create-project* 1 {:profile-id (:id profile-1)
                                           :team-id (:default-team-id profile-1)})
          project-2 (th/create-project* 2 {:profile-id (:id profile-2)
                                           :team-id (:default-team-id profile-2)})
          data      {::th/type :create-file
                     ::rpc/profile-id (:id profile-1)
                      :project-id (:id project-1)}
          check-ok! (fn [n]
                      (let [data (assoc data :name (str "file" n))
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))
          check-ko! (fn [n]
                      (let [data (assoc data :name (str "file" n))
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (not (th/success? out)))
                        (let [error (:error out)]
                          (t/is (= :restriction (th/ex-type error)))
                          (t/is (= :max-quote-reached (th/ex-code error)))
                          (t/is (= "files-per-project" (:target (ex-data error)))))))]

      (th/db-insert! :usage-quote
                     {:project-id (:id project-2)
                      :target "files-per-project"
                      :quote 100})

      (th/db-insert! :usage-quote
                     {:team-id (:team-id project-2)
                      :target "files-per-project"
                      :quote 100})

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-2)
                      :target "files-per-project"
                      :quote 100})


      (check-ok! 1)
      (check-ko! 2)

      (th/db-insert! :usage-quote
                     {:project-id (:id project-1)
                      :target "files-per-project"
                      :quote 2})

      (th/db-insert! :usage-quote
                     {:project-id (:id project-1)
                      :profile-id (:id profile-2)
                      :target "files-per-project"
                      :quote 100})

      (check-ok! 2)
      (check-ko! 3)

      (th/db-insert! :usage-quote
                     {:team-id (:team-id project-1)
                      :target "files-per-project"
                      :quote 3})

      (th/db-insert! :usage-quote
                     {:team-id (:team-id project-1)
                      :profile-id (:id profile-2)
                      :target "files-per-project"
                      :quote 100})


      (check-ok! 3)
      (check-ko! 4)

      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-1)
                      :target "files-per-project"
                      :quote 4})

      (check-ok! 4)
      (check-ko! 5)

      )))
