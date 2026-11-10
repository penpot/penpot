;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-comment-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.comments :as comments]
   [app.rpc.cond :as cond]
   [app.rpc.quotes :as-alias quotes]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest comment-and-threads-crud
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-teams-per-profile 200})}]

    (let [profile-1  (th/create-profile* 1 {:is-active true})
          profile-2  (th/create-profile* 2 {:is-active true})

          team       (th/create-team* 1 {:profile-id (:id profile-1)})
          ;; role      (th/create-team-role* {:team-id (:id team)
          ;;                                  :profile-id (:id profile-2)
          ;;                                  :role :admin})

          project    (th/create-project* 1 {:team-id (:id team)
                                            :profile-id (:id profile-1)})
          file-1     (th/create-file* 1 {:profile-id (:id profile-1)
                                         :project-id (:id project)})
          file-2     (th/create-file* 2 {:profile-id (:id profile-1)
                                         :project-id (:id project)})
          page-id    (get-in file-1 [:data :pages 0])]

      (t/testing "comment thread creation"
        (let [data {::th/type :create-comment-thread
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file-1)
                    :page-id page-id
                    :position (gpt/point 0)
                    :content "hello world"
                    :frame-id uuid/zero}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [result (:result out)]
            (t/is (uuid? (:id result)))
            (t/is (uuid? (:file-id result)))
            (t/is (uuid? (:page-id result)))
            (t/is (uuid? (:comment-id result)))
            (t/is (= (:file-id result) (:id file-1)))
            (t/is (= (:page-id result) page-id)))))

      (t/testing "comment thread status update"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              ;; comment (-> (th/db-query :comment {:thread-id (:id thread)}) first)
              data    {::th/type :update-comment-thread-status
                       ::rpc/profile-id (:id profile-1)
                       :id (:id thread)}
              status  (th/db-get :comment-thread-status
                                 {:thread-id (:id thread)
                                  :profile-id (:id profile-1)})]


          (t/is (= (:modified-at status) (:modified-at thread)))

          (let [{:keys [result] :as out} (th/command! data)]
            (t/is (th/success? out))
            (t/is (ct/inst? (:modified-at result))))

          (let [status' (th/db-get :comment-thread-status
                                   {:thread-id (:id thread)
                                    :profile-id (:id profile-1)})]
            (t/is (not= (:modified-at status') (:modified-at thread))))))

      (t/testing "comment thread status update 2"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data    {::th/type :update-comment-thread-status
                       ::rpc/profile-id (:id profile-2)
                       :id (:id thread)}]

          (let [{:keys [error] :as out} (th/command! data)]
            ;; (th/print-result! out)
            (t/is (not (th/success? out)))
            (t/is (= :not-found (th/ex-type error))))))

      (t/testing "update comment thread"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data    {::th/type :update-comment-thread
                       ::rpc/profile-id (:id profile-1)
                       :is-resolved true
                       :id (:id thread)}]

          (t/is (false? (:is-resolved thread)))

          (let [{:keys [result] :as out} (th/command! data)]
            (t/is (th/success? out))
            (t/is (nil? result)))

          (let [thread (th/db-get :comment-thread {:id (:id thread)})]
            (t/is (true? (:is-resolved thread))))))

      (t/testing "create comment"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data    {::th/type :create-comment
                       ::rpc/profile-id (:id profile-1)
                       :thread-id (:id thread)
                       :content "comment 2"}]
          (let [{:keys [result] :as out} (th/command! data)
                {:keys [modified-at]}    (th/db-get :comment-thread-status
                                                    {:thread-id (:id thread)
                                                     :profile-id (:id profile-1)})]
            ;; (th/print-result! out)
            (t/is (th/success? out))
            (t/is (uuid? (:id result)))
            (t/is (= (:owner-id result) (:id profile-1)))
            (t/is (:modified-at result) modified-at))))

      (t/testing "update comment"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              comment (-> (th/db-query :comment {:thread-id (:id thread) :content "comment 2"}) first)
              data    {::th/type :update-comment
                       ::rpc/profile-id (:id profile-1)
                       :id (:id comment)
                       :content "comment 2 mod"}]
          (let [{:keys [result] :as out} (th/command! data)]
            ;; (th/print-result! out)
            (t/is (th/success? out))
            (t/is (nil? result)))

          (let [comment' (th/db-get :comment {:id (:id comment)})]
            (t/is (not= (:modified-at comment) (:modified-at comment')))
            (t/is (= (:content data) (:content comment'))))))

      (t/testing "retrieve threads"
        (let [data {::th/type :get-comment-threads
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file-1)}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [[thread :as result] (:result out)]
            (t/is (= 1 (count result)))
            (t/is (= "Page 1" (:page-name thread)))
            (t/is (= "hello world" (:content thread)))
            (t/is (= 2 (:count-comments thread)))
            (t/is (true? (:is-resolved thread))))))


      (t/testing "unread comment threads"
        (let [thread (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data   {::th/type :get-unread-comment-threads
                      ::rpc/profile-id (:id profile-1)}]

          (let [{:keys [result] :as out} (th/command! (assoc data :team-id (:default-team-id profile-1)))]
            (t/is (th/success? out))
            (t/is (= [] result)))

          (let [{:keys [error] :as out} (th/command! (assoc data :team-id (:default-team-id profile-2)))]
            (t/is (not (th/success? out)))
            (t/is (= :not-found (th/ex-type error))))

          (let [{:keys [result] :as out} (th/command! (assoc data :team-id (:id team)))]
            ;; (th/print-result! out)
            (t/is (th/success? out))
            (let [[thread :as result] (:result out)]
              (t/is (= 0 (count result)))))

          (let [data {::th/type :update-comment-thread-status
                      ::rpc/profile-id (:id profile-1)
                      :id (:id thread)}
                out  (th/command! data)]
            (t/is (th/success? out)))

          (let [{:keys [result] :as out} (th/command! (assoc data :team-id (:id team)))]
            ;; (th/print-result! out)
            (t/is (th/success? out))
            (let [result (:result out)]
              (t/is (= 0 (count result)))))))

      (t/testing "get comment thread"
        (let [thread (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data   {::th/type :get-comment-thread
                      ::rpc/profile-id (:id profile-1)
                      :file-id (:id file-1)
                      :id (:id thread)}]

          (let [{:keys [result] :as out} (th/command! data)]
            ;; (th/print-result! out)
            (t/is (th/success? out))
            (t/is (= (:id thread) (:id result))))))

      (t/testing "get comments"
        (let [thread (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data   {::th/type :get-comments
                      ::rpc/profile-id (:id profile-1)
                      :thread-id (:id thread)}
              out    (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [comments (:result out)]
            (t/is (= 2 (count comments))))))

      (t/testing "get profiles"
        (let [data {::th/type :get-profiles-for-file-comments
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file-1)}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [[profile :as profiles] (:result out)]
            (t/is (= 1 (count profiles)))
            (t/is (= (:id profile-1) (:id profile))))))

      (t/testing "get profiles 2"
        (let [data {::th/type :get-profiles-for-file-comments
                    ::rpc/profile-id (:id profile-2)
                    :file-id (:id file-1)}
              out  (th/command! data)]
          ;; (th/print-result! out)
          (t/is (not (th/success? out)))
          (t/is (= :not-found (th/ex-type (:error out))))))

      (t/testing "delete comment"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              comment (-> (th/db-query :comment {:thread-id (:id thread) :content "comment 2 mod"}) first)
              data    {::th/type :delete-comment
                       ::rpc/profile-id (:id profile-2)
                       :id (:id comment)}
              out     (th/command! data)]

          ;; (th/print-result! out)
          (t/is (not (th/success? out)))
          (t/is (= :not-found (th/ex-type (:error out))))
          (let [comments (th/db-query :comment {:thread-id (:id thread)})]
            (t/is (= 2 (count comments))))))

      (t/testing "delete comment 2"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              comment (-> (th/db-query :comment {:thread-id (:id thread) :content "comment 2 mod"}) first)
              data    {::th/type :delete-comment
                       ::rpc/profile-id (:id profile-1)
                       :id (:id comment)}
              out     (th/command! data)]

          ;; (th/print-result! out)
          (t/is (th/success? out))
          (let [comments (th/db-query :comment {:thread-id (:id thread)})]
            (t/is (= 1 (count comments))))))

      (t/testing "delete comment thread"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data    {::th/type :delete-comment-thread
                       ::rpc/profile-id (:id profile-2)
                       :id (:id thread)}
              out     (th/command! data)]

          ;; (th/print-result! out)
          (t/is (not (th/success? out)))
          (t/is (= :not-found (th/ex-type (:error out))))
          (let [threads (th/db-query :comment-thread {:file-id (:id file-1)})]
            (t/is (= 1 (count threads))))))

      (t/testing "delete comment thread 2"
        (let [thread  (-> (th/db-query :comment-thread {:file-id (:id file-1)}) first)
              data    {::th/type :delete-comment-thread
                       ::rpc/profile-id (:id profile-1)
                       :id (:id thread)}
              out     (th/command! data)]

          ;; (th/print-result! out)
          (t/is (th/success? out))

          (let [threads (th/db-query :comment-thread {:file-id (:id file-1)})]
            (t/is (= 0 (count threads)))))))))

(t/deftest share-link-who-comment-team-cannot-comment
  (let [owner    (th/create-profile* 1 {:is-active true})
        outsider (th/create-profile* 2 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        page-id  (get-in file [:data :pages 0])

        share    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{page-id}
                               :who-comment "team"
                               :who-inspect "all"})
        share-id (get-in share [:result :id])]

    (t/testing "outsider with who-comment=team share-link cannot get-comment-threads"
      (let [out (th/command! {::th/type :get-comment-threads
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "outsider with who-comment=team share-link cannot create-comment-thread"
      (let [out (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :page-id page-id
                              :position (gpt/point 0)
                              :content "outsider comment"
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "outsider with who-comment=team share-link cannot get-profiles-for-file-comments"
      (let [out (th/command! {::th/type :get-profiles-for-file-comments
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest share-link-who-comment-all-can-comment
  (let [owner    (th/create-profile* 1 {:is-active true})
        outsider (th/create-profile* 2 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        page-id  (get-in file [:data :pages 0])

        share    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{page-id}
                               :who-comment "all"
                               :who-inspect "all"})
        share-id (get-in share [:result :id])]

    (t/testing "outsider with who-comment=all share-link can get-comment-threads"
      (let [out (th/command! {::th/type :get-comment-threads
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "outsider with who-comment=all share-link can create-comment-thread"
      (let [out (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :page-id page-id
                              :position (gpt/point 0)
                              :content "outsider comment"
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (th/success? out))))))

(t/deftest share-link-page-scope-enforced
  (let [owner    (th/create-profile* 1 {:is-active true})
        outsider (th/create-profile* 2 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})

        page-a   (get-in file [:data :pages 0])
        page-b   (uuid/random)

        _        (th/command! {::th/type :update-file
                               ::rpc/profile-id (:id owner)
                               :id (:id file)
                               :session-id (uuid/random)
                               :revn 0
                               :vern 0
                               :changes [{:type :add-page
                                          :id page-b
                                          :page {:id page-b
                                                 :name "Page B"
                                                 :options {}
                                                 :objects {}}}]})

        thread-a (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :page-id page-a
                               :position (gpt/point 0)
                               :content "comment on page A"
                               :frame-id uuid/zero})
        thread-b (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :page-id page-b
                               :position (gpt/point 0)
                               :content "comment on page B"
                               :frame-id uuid/zero})

        thread-a-id (get-in thread-a [:result :id])
        thread-b-id (get-in thread-b [:result :id])

        share    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{page-a}
                               :who-comment "all"
                               :who-inspect "all"})
        share-id (get-in share [:result :id])]

    (t/testing "share-link holder can get-comment-threads for shared page only"
      (let [out (th/command! {::th/type :get-comment-threads
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :share-id share-id})
            result (:result out)]
        (t/is (th/success? out))
        (t/is (= 1 (count result)))
        (t/is (= page-a (:page-id (first result))))))

    (t/testing "share-link holder cannot get-comment-thread for unshared page"
      (let [out (th/command! {::th/type :get-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :id thread-b-id
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder can get-comment-thread for shared page"
      (let [out (th/command! {::th/type :get-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :id thread-a-id
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "share-link holder cannot get-comments for thread on unshared page"
      (let [out (th/command! {::th/type :get-comments
                              ::rpc/profile-id (:id outsider)
                              :thread-id thread-b-id
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest share-link-page-scope-enforced-for-comment-profiles
  (let [owner    (th/create-profile* 1 {:is-active true})
        outsider (th/create-profile* 2 {:is-active true})
        member   (th/create-profile* 3 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        _        (th/create-team-role* {:team-id (:id team)
                                        :profile-id (:id member)
                                        :role :editor})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})

        page-a   (get-in file [:data :pages 0])
        page-b   (uuid/random)

        _        (th/command! {::th/type :update-file
                               ::rpc/profile-id (:id owner)
                               :id (:id file)
                               :session-id (uuid/random)
                               :revn 0
                               :vern 0
                               :changes [{:type :add-page
                                          :id page-b
                                          :page {:id page-b
                                                 :name "Page B"
                                                 :options {}
                                                 :objects {}}}]})

        thread-a (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :page-id page-a
                               :position (gpt/point 0)
                               :content "comment on page A"
                               :frame-id uuid/zero})

        thread-b (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id member)
                               :file-id (:id file)
                               :page-id page-b
                               :position (gpt/point 0)
                               :content "comment on page B"
                               :frame-id uuid/zero})

        thread-a-id (get-in thread-a [:result :id])
        thread-b-id (get-in thread-b [:result :id])
        thread-b-comment-id (get-in thread-b [:result :comment-id])

        share    (th/command! {::th/type :create-share-link
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :pages #{page-a}
                               :who-comment "all"
                               :who-inspect "all"})
        share-id (get-in share [:result :id])

        ;; A link without pages grants access to nothing.
        empty-share    (th/command! {::th/type :create-share-link
                                     ::rpc/profile-id (:id owner)
                                     :file-id (:id file)
                                     :pages #{}
                                     :who-comment "all"
                                     :who-inspect "all"})
        empty-share-id (get-in empty-share [:result :id])]

    (t/testing "the comment threads are created"
      (t/is (th/success? thread-a))
      (t/is (th/success? thread-b)))

    (t/testing "share-link holder only gets the profiles of the shared page commenters"
      (let [out    (th/command! {::th/type :get-profiles-for-file-comments
                                 ::rpc/profile-id (:id outsider)
                                 :file-id (:id file)
                                 :share-id share-id})
            emails (set (map :email (:result out)))]
        (t/is (th/success? out))
        (t/is (contains? emails (:email owner)))
        (t/is (contains? emails (:email outsider)))
        (t/is (not (contains? emails (:email member))))))

    (t/testing "share-link holder cannot update the status of a thread of an unshared page"
      (let [out (th/command! {::th/type :update-comment-thread-status
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-id
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot update a comment of an unshared page"
      (let [out (th/command! {::th/type :update-comment
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-comment-id
                              :content "outsider edit on page B"
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot delete a comment of an unshared page"
      (let [out (th/command! {::th/type :delete-comment
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-comment-id
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot delete a thread of an unshared page"
      (let [out (th/command! {::th/type :delete-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-id
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot create a thread on an unshared page"
      (let [out (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :page-id page-b
                              :position (gpt/point 0)
                              :content "comment on page B"
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot move a thread of an unshared page"
      (let [out (th/command! {::th/type :update-comment-thread-position
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-id
                              :position (gpt/point 10 10)
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder cannot resize a thread of an unshared page"
      (let [out (th/command! {::th/type :update-comment-thread-frame
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-id
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder can move a thread of the shared page"
      (let [out (th/command! {::th/type :update-comment-thread-position
                              ::rpc/profile-id (:id outsider)
                              :id thread-a-id
                              :position (gpt/point 10 10)
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "share-link holder can resize a thread of the shared page"
      (let [out (th/command! {::th/type :update-comment-thread-frame
                              ::rpc/profile-id (:id outsider)
                              :id thread-a-id
                              :frame-id uuid/zero
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "share-link holder cannot reply to a thread of an unshared page"
      (let [out (th/command! {::th/type :create-comment
                              ::rpc/profile-id (:id outsider)
                              :thread-id thread-b-id
                              :content "outsider reply on page B"
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder can reply to a thread of the shared page"
      (let [out (th/command! {::th/type :create-comment
                              ::rpc/profile-id (:id outsider)
                              :thread-id thread-a-id
                              :content "outsider reply on page A"
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "share-link holder cannot resolve a thread of an unshared page"
      (let [out (th/command! {::th/type :update-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :id thread-b-id
                              :is-resolved true
                              :share-id share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link holder can resolve a thread of the shared page"
      (let [out (th/command! {::th/type :update-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :id thread-a-id
                              :is-resolved true
                              :share-id share-id})]
        (t/is (th/success? out))))

    (t/testing "share-link holder can write on the shared page"
      (let [thread (th/command! {::th/type :create-comment-thread
                                 ::rpc/profile-id (:id outsider)
                                 :file-id (:id file)
                                 :page-id page-a
                                 :position (gpt/point 0)
                                 :content "outsider comment on page A"
                                 :frame-id uuid/zero
                                 :share-id share-id})
            thread-id  (get-in thread [:result :id])
            comment-id (get-in thread [:result :comment-id])]

        (t/is (th/success? thread))

        (t/testing "can mark a thread as read"
          (let [out (th/command! {::th/type :update-comment-thread-status
                                  ::rpc/profile-id (:id outsider)
                                  :id thread-id
                                  :share-id share-id})]
            (t/is (th/success? out))))

        (t/testing "can edit its comment"
          (let [out (th/command! {::th/type :update-comment
                                  ::rpc/profile-id (:id outsider)
                                  :id comment-id
                                  :content "outsider edit on page A"
                                  :share-id share-id})]
            (t/is (th/success? out))))

        (t/testing "can delete its comment"
          (let [out (th/command! {::th/type :delete-comment
                                  ::rpc/profile-id (:id outsider)
                                  :id comment-id
                                  :share-id share-id})]
            (t/is (th/success? out))))

        (t/testing "can delete its thread"
          (let [out (th/command! {::th/type :delete-comment-thread
                                  ::rpc/profile-id (:id outsider)
                                  :id thread-id
                                  :share-id share-id})]
            (t/is (th/success? out))))))

    (t/testing "share-link without pages does not leak the commenter profiles"
      (let [out    (th/command! {::th/type :get-profiles-for-file-comments
                                 ::rpc/profile-id (:id outsider)
                                 :file-id (:id file)
                                 :share-id empty-share-id})
            emails (set (map :email (:result out)))]
        (t/is (th/success? out))
        (t/is (= #{(:email outsider)} emails))))

    (t/testing "share-link without pages cannot create a thread"
      (let [out (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :page-id page-a
                              :position (gpt/point 0)
                              :content "outsider comment on page A"
                              :frame-id uuid/zero
                              :share-id empty-share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link without pages lists no comment threads"
      (let [out (th/command! {::th/type :get-comment-threads
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :share-id empty-share-id})]
        (t/is (th/success? out))
        (t/is (= [] (:result out)))))

    (t/testing "share-link without pages cannot get a comment thread"
      (let [out (th/command! {::th/type :get-comment-thread
                              ::rpc/profile-id (:id outsider)
                              :file-id (:id file)
                              :id thread-a-id
                              :share-id empty-share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "share-link without pages cannot get the comments of a thread"
      (let [out (th/command! {::th/type :get-comments
                              ::rpc/profile-id (:id outsider)
                              :thread-id thread-a-id
                              :share-id empty-share-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest membership-can-still-comment
  (let [owner   (th/create-profile* 1 {:is-active true})
        member  (th/create-profile* 2 {:is-active true})

        team    (th/create-team* 1 {:profile-id (:id owner)})
        _       (th/create-team-role* {:team-id (:id team)
                                       :profile-id (:id member)
                                       :role :editor})
        project (th/create-project* 1 {:team-id (:id team)
                                       :profile-id (:id owner)})
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id (:id project)})
        page-id (get-in file [:data :pages 0])

        _       (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :page-id page-id
                              :position (gpt/point 0)
                              :content "owner comment"
                              :frame-id uuid/zero})

        thread  (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id member)
                              :file-id (:id file)
                              :page-id page-id
                              :position (gpt/point 0)
                              :content "member comment"
                              :frame-id uuid/zero})]

    (t/testing "team member can get-comment-threads without share-id"
      (let [out (th/command! {::th/type :get-comment-threads
                              ::rpc/profile-id (:id member)
                              :file-id (:id file)})]
        (t/is (th/success? out))))

    (t/testing "team member can create-comment-thread without share-id"
      (t/is (th/success? thread)))

    (t/testing "team member can move a thread of the file"
      (let [out (th/command! {::th/type :update-comment-thread-position
                              ::rpc/profile-id (:id member)
                              :id (get-in thread [:result :id])
                              :position (gpt/point 10 10)
                              :frame-id uuid/zero})]
        (t/is (th/success? out))))

    (t/testing "team member can resize a thread of the file"
      (let [out (th/command! {::th/type :update-comment-thread-frame
                              ::rpc/profile-id (:id member)
                              :id (get-in thread [:result :id])
                              :frame-id uuid/zero})]
        (t/is (th/success? out))))

    (t/testing "team member can reply to a thread of the file"
      (let [out (th/command! {::th/type :create-comment
                              ::rpc/profile-id (:id member)
                              :thread-id (get-in thread [:result :id])
                              :content "member reply"})]
        (t/is (th/success? out))))

    (t/testing "team member can resolve a thread of the file"
      (let [out (th/command! {::th/type :update-comment-thread
                              ::rpc/profile-id (:id member)
                              :id (get-in thread [:result :id])
                              :is-resolved true})]
        (t/is (th/success? out))))

    (t/testing "team member gets the profiles of all the file commenters"
      (let [out    (th/command! {::th/type :get-profiles-for-file-comments
                                 ::rpc/profile-id (:id member)
                                 :file-id (:id file)})
            emails (set (map :email (:result out)))]
        (t/is (th/success? out))
        (t/is (contains? emails (:email member)))
        (t/is (contains? emails (:email owner)))))))

(t/deftest comment-audit-events-carry-team-id
  ;; Comment events carry the file id, so the resolver could find the team; the
  ;; command already has the file with its team id and sets it without a join.
  (with-mocks [mock {:target 'app.config/get
                     :return (th/config-get-mock
                              {:quotes-teams-per-profile 200})}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id profile)})
          project (th/create-project* 1 {:team-id (:id team)
                                         :profile-id (:id profile)})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:id project)})
          page-id (get-in file [:data :pages 0])
          thread  (th/command! {::th/type :create-comment-thread
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)
                                :page-id page-id
                                :position (gpt/point 0)
                                :content "hello"
                                :frame-id uuid/zero})
          comment (th/command! {::th/type :create-comment
                                ::rpc/profile-id (:id profile)
                                :thread-id (get-in thread [:result :id])
                                :content "comment"})]
      (t/is (= (:id team)
               (get-in (meta (:result thread)) [:app.loggers.audit/props :team-id])))
      (t/is (= (:id team)
               (get-in (meta (:result comment)) [:app.loggers.audit/props :team-id]))))))

(t/deftest mark-all-threads-as-read-requires-access-to-the-threads
  (let [owner    (th/create-profile* 1 {:is-active true})
        stranger (th/create-profile* 2 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        page-id  (get-in file [:data :pages 0])

        thread   (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :page-id page-id
                               :position (gpt/point 0)
                               :content "private thread"
                               :frame-id uuid/zero})
        thread-id (get-in thread [:result :id])]

    (t/testing "the sibling rpc already rejects a profile without access"
      (let [out (th/command! {::th/type :update-comment-thread-status
                              ::rpc/profile-id (:id stranger)
                              :id thread-id})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "mark-all-threads-as-read rejects a profile without access"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id stranger)
                              :threads [thread-id]})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out)))))

      (t/is (nil? (th/db-get :comment-thread-status
                             {:thread-id thread-id
                              :profile-id (:id stranger)}))))))

(t/deftest mark-all-threads-as-read-behaviour
  (let [owner    (th/create-profile* 1 {:is-active true})
        member   (th/create-profile* 2 {:is-active true})
        viewer   (th/create-profile* 3 {:is-active true})
        outsider (th/create-profile* 4 {:is-active true})

        team     (th/create-team* 1 {:profile-id (:id owner)})
        _        (th/create-team-role* {:team-id (:id team)
                                        :profile-id (:id member)
                                        :role :editor})
        _        (th/create-team-role* {:team-id (:id team)
                                        :profile-id (:id viewer)
                                        :role :viewer})
        project  (th/create-project* 1 {:team-id (:id team)
                                        :profile-id (:id owner)})
        file     (th/create-file* 1 {:profile-id (:id owner)
                                     :project-id (:id project)})
        page-id  (get-in file [:data :pages 0])

        thread   (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id owner)
                               :file-id (:id file)
                               :page-id page-id
                               :position (gpt/point 0)
                               :content "hello"
                               :frame-id uuid/zero})
        thread-id (get-in thread [:result :id])]

    (t/testing "the thread is unread for a team member"
      (let [out (th/command! {::th/type :get-unread-comment-threads
                              ::rpc/profile-id (:id member)
                              :team-id (:id team)})]
        (t/is (th/success? out))
        (t/is (= 1 (count (:result out))))))

    (t/testing "a team member can mark the threads as read"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads [thread-id]})]
        (t/is (th/success? out)))

      (let [out (th/command! {::th/type :get-unread-comment-threads
                              ::rpc/profile-id (:id member)
                              :team-id (:id team)})]
        (t/is (th/success? out))
        (t/is (= 0 (count (:result out))))))

    (t/testing "a viewer of the team can mark the threads as read"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id viewer)
                              :threads [thread-id]})]
        (t/is (th/success? out))))

    (t/testing "an unknown thread is rejected"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads [(uuid/random)]})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))

    (t/testing "more threads than the allowed maximum are rejected"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads (vec (repeatedly 501 uuid/random))})]
        (t/is (not (th/success? out)))
        (t/is (= :validation (th/ex-type (:error out))))))

    (t/testing "a list with an inaccessible thread writes nothing"
      (let [other-team    (th/create-team* 2 {:profile-id (:id owner)})
            other-project (th/create-project* 2 {:team-id (:id other-team)
                                                 :profile-id (:id owner)})
            other-file    (th/create-file* 2 {:profile-id (:id owner)
                                              :project-id (:id other-project)})
            other-thread  (th/command! {::th/type :create-comment-thread
                                        ::rpc/profile-id (:id owner)
                                        :file-id (:id other-file)
                                        :page-id (get-in other-file [:data :pages 0])
                                        :position (gpt/point 0)
                                        :content "other team"
                                        :frame-id uuid/zero})
            other-thread-id (get-in other-thread [:result :id])

            accessible-thread (th/command! {::th/type :create-comment-thread
                                            ::rpc/profile-id (:id owner)
                                            :file-id (:id file)
                                            :page-id page-id
                                            :position (gpt/point 0)
                                            :content "accessible"
                                            :frame-id uuid/zero})
            accessible-thread-id (get-in accessible-thread [:result :id])

            out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads [accessible-thread-id other-thread-id]})]

        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))
        (t/is (nil? (th/db-get :comment-thread-status
                               {:thread-id accessible-thread-id
                                :profile-id (:id member)})))
        (t/is (nil? (th/db-get :comment-thread-status
                               {:thread-id other-thread-id
                                :profile-id (:id member)})))))

    (t/testing "a profile without any relation with the team is rejected"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id outsider)
                              :threads [thread-id]})]
        (t/is (not (th/success? out)))
        (t/is (= :not-found (th/ex-type (:error out))))))))

(t/deftest mark-all-threads-as-read-edge-cases
  (let [owner   (th/create-profile* 1 {:is-active true})
        member  (th/create-profile* 2 {:is-active true})
        viewer  (th/create-profile* 3 {:is-active true})

        team    (th/create-team* 1 {:profile-id (:id owner)})
        _       (th/create-team-role* {:team-id (:id team)
                                       :profile-id (:id member)
                                       :role :editor})
        _       (th/create-team-role* {:team-id (:id team)
                                       :profile-id (:id viewer)
                                       :role :viewer})
        project (th/create-project* 1 {:team-id (:id team)
                                       :profile-id (:id owner)})
        file    (th/create-file* 1 {:profile-id (:id owner)
                                    :project-id (:id project)})
        page-id (get-in file [:data :pages 0])

        thread  (th/command! {::th/type :create-comment-thread
                              ::rpc/profile-id (:id owner)
                              :file-id (:id file)
                              :page-id page-id
                              :position (gpt/point 0)
                              :content "hello"
                              :frame-id uuid/zero})
        thread-id (get-in thread [:result :id])]

    (t/testing "repeated thread ids are accepted and written once"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads [thread-id thread-id]})]
        (t/is (th/success? out))
        (t/is (= 1 (count (th/db-query :comment-thread-status
                                       {:thread-id thread-id
                                        :profile-id (:id member)}))))))

    (t/testing "an empty list of threads is accepted and writes nothing"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id viewer)
                              :threads []})]
        (t/is (th/success? out))
        (t/is (= 0 (count (th/db-query :comment-thread-status
                                       {:thread-id thread-id
                                        :profile-id (:id viewer)}))))))

    (t/testing "marking a thread as read twice keeps a single status row"
      (let [out (th/command! {::th/type :mark-all-threads-as-read
                              ::rpc/profile-id (:id member)
                              :threads [thread-id]})]
        (t/is (th/success? out)))

      (t/is (= 1 (count (th/db-query :comment-thread-status
                                     {:thread-id thread-id
                                      :profile-id (:id member)}))))

      (let [out (th/command! {::th/type :get-unread-comment-threads
                              ::rpc/profile-id (:id member)
                              :team-id (:id team)})]
        (t/is (th/success? out))
        (t/is (= 0 (count (:result out))))))))
