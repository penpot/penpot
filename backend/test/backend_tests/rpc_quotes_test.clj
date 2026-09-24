;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [datoteka.fs :as fs]
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
                      ::rpc/profile-id (:id profile-1)
                      :team-id team-id}

          check-ok! (fn [name]
                      (let [data (assoc data :name (str "project" name))
                            out  (th/command! data)]
                        ;; (th/print-result! out)
                        (t/is (nil? (:error out)))
                        (t/is (some? (:result out)))))

          check-ko! (fn [name]
                      ;; create second project
                      (let [data (assoc data :name (str "project" name))
                            out  (th/command! data)]
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
      (check-ko! 5))))

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
      (check-ko! 5))))

(t/deftest media-storage-bytes-per-team-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-media-storage-bytes-per-team 1000})}]

    (let [profile-1 (th/create-profile* 1)
          profile-2 (th/create-profile* 2)
          team-id   (:default-team-id profile-1)
          data      {::quotes/id ::quotes/media-storage-bytes-per-team
                     ::quotes/profile-id (:id profile-1)
                     ::quotes/team-id team-id
                     ::quotes/incr 500}

          check-ok! (fn [msg]
                      (quotes/check! th/*system* data)
                      (t/is (true? true) msg))
          check-ko! (fn [msg]
                      (try
                        (quotes/check! th/*system* data)
                        (t/is false (str msg " — expected exception but none thrown"))
                        (catch Exception e
                          (let [ed (ex-data e)]
                            (t/is (= :restriction (:type ed)))
                            (t/is (= :max-quote-reached (:code ed)))
                            (t/is (= "media-storage-bytes-per-team" (:target ed)))))))]

      ;; Under default limit (1000) with incr=500 and no existing storage — ok
      (check-ok! "first check under limit")

      ;; Insert a quote row for another profile on the same team — does not help
      (th/db-insert! :usage-quote
                     {:profile-id (:id profile-2)
                      :target "media-storage-bytes-per-team"
                      :quote 100})

      ;; Insert a team+profile quote that is still too low
      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :profile-id (:id profile-2)
                      :target "media-storage-bytes-per-team"
                      :quote 200})

      ;; Insert a team-level quote (no profile) that is still too low
      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :target "media-storage-bytes-per-team"
                      :quote 400})

      ;; total=0, incr=500, best quote=400 → 0+500 > 400 → blocked
      (check-ko! "blocked by team-level quote")

      ;; Insert a team+profile quote that allows it
      (th/db-insert! :usage-quote
                     {:team-id team-id
                      :profile-id (:id profile-1)
                      :target "media-storage-bytes-per-team"
                      :quote 1000})

      ;; total=0, incr=500, best quote=1000 → 0+500 <= 1000 → ok
      (check-ok! "allowed by team+profile quote"))))

(t/deftest media-storage-bytes-quote-deduped
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-media-storage-bytes-per-team 1100})}]

    (let [prof    (th/create-profile* 1)
          team-id (:default-team-id prof)
          proj    (th/create-project* 1 {:profile-id (:id prof)
                                         :team-id team-id})
          file1   (th/create-file* 1 {:profile-id (:id prof)
                                      :project-id (:id proj)
                                      :is-shared false})
          file2   (th/create-file* 2 {:profile-id (:id prof)
                                      :project-id (:id proj)
                                      :is-shared false})

          ;; One physical storage object of 500 bytes
          so-id   (uuid/random)
          _       (th/db-insert! :storage-object {:id so-id
                                                  :size 500
                                                  :backend "test"})

          ;; Two file_media_object rows pointing at the SAME storage object
          ;; (simulates the deduplication path: same content uploaded twice)
          _       (th/create-file-media-object*
                   {:file-id (:id file1) :media-id so-id
                    :name "icon" :mtype "image/svg+xml"})
          _       (th/create-file-media-object*
                   {:file-id (:id file2) :media-id so-id
                    :name "icon" :mtype "image/svg+xml"})

          data    {::quotes/id ::quotes/media-storage-bytes-per-team
                   ::quotes/profile-id (:id prof)
                   ::quotes/team-id team-id
                   ::quotes/incr 200}]

      ;; Physical size is 500. With UNION (correct), total=500, 500+200=700 ≤ 1100 → ok.
      ;; With UNION ALL (buggy), total=1000, 1000+200=1200 > 1100 → rejected.
      (quotes/check! th/*system* data)
      (t/is (true? true) "deduped storage counted once, under quota"))))

(t/deftest media-upload-enforces-storage-quote
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-media-storage-bytes-per-team 100})}]

    (let [prof  (th/create-profile* 1)
          proj  (th/create-project* 1 {:profile-id (:id prof)
                                       :team-id (:default-team-id prof)})
          file  (th/create-file* 1 {:profile-id (:id prof)
                                    :project-id (:id proj)
                                    :is-shared false})
          mfile {:filename "sample.jpg"
                 :path (th/tempfile "backend_tests/test_files/sample.jpg")
                 :mtype "image/jpeg"
                 :size 312043}

          params {::th/type :upload-file-media-object
                  ::rpc/profile-id (:id prof)
                  :file-id (:id file)
                  :is-local true
                  :name "testfile"
                  :content mfile}

          out    (th/command! params)]

      ;; 312043 bytes > 100 byte limit → should be rejected
      (t/is (not (th/success? out)))
      (let [error (:error out)]
        (t/is (= :restriction (th/ex-type error)))
        (t/is (= :max-quote-reached (th/ex-code error)))
        (t/is (= "media-storage-bytes-per-team" (:target (ex-data error))))))))
