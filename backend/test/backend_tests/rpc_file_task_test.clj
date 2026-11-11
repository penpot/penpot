;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-task-test
  (:require
   [app.common.features :as cfeat]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- task-flags
  "The test flags plus `:branching`. Read at run time, inside the
  `th/state-init` fixture, so the flags it turns off (the admin
  console among them) stay off: a value captured when the namespace
  loads would carry the production defaults instead."
  []
  (conj cf/flags :branching))

(defn- page-id-of
  [profile file-id]
  (-> (th/command! {::th/type :get-file
                    ::rpc/profile-id (:id profile)
                    :id file-id})
      :result :data :pages first))

(defn- update-file*
  [profile file-id changes]
  (let [f (th/db-get :file {:id file-id})]
    (th/command! {::th/type :update-file
                  ::rpc/profile-id (:id profile)
                  :id file-id
                  :session-id (uuid/random)
                  :revn (:revn f)
                  :vern (:vern f)
                  :features cfeat/supported-features
                  :changes changes})))

(defn- add-rect*
  [profile file-id page-id rect-id name]
  (update-file* profile file-id
                [{:type :add-obj
                  :page-id page-id
                  :id rect-id
                  :parent-id uuid/zero
                  :frame-id uuid/zero
                  :obj (cts/setup-shape
                        {:id rect-id :name name :type :rect
                         :parent-id uuid/zero :frame-id uuid/zero})}]))

(defn- setup-review
  "A file with a rect on main, a branch of it and an open design review of
  the branch. Returns the ids the tests need."
  []
  (let [author   (th/create-profile* 1 {:is-active true})
        reviewer (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
        page-id  (page-id-of author (:id file))
        rect-id  (uuid/random)]
    (th/create-team-role* {:team-id (:default-team-id author)
                           :profile-id (:id reviewer)
                           :role :editor})
    (add-rect* author (:id file) page-id rect-id "Pay button")
    (let [branch (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id author)
                                        :file-id (:id file)
                                        :name "feature-login"}))
          review (:result (th/command! {::th/type :create-pull-request
                                        ::rpc/profile-id (:id author)
                                        :branch-id (:id branch)
                                        :title "Checkout redesign"
                                        :reviewers [(:id reviewer)]}))]
      {:author author
       :reviewer reviewer
       :file file
       :page-id page-id
       :rect-id rect-id
       :branch branch
       :branch-file-id (:branch-file-id branch)
       :review review})))

(defn- create-task*
  [profile params]
  (th/command! (merge {::th/type :create-task
                       ::rpc/profile-id (:id profile)
                       :frame-id uuid/zero
                       :position (gpt/point 0)
                       :title "Use the secondary token on the pay button"}
                      params)))

(defn- set-status*
  [profile task-id status]
  (th/command! {::th/type :set-task-status
                ::rpc/profile-id (:id profile)
                :id task-id
                :status status}))

(t/deftest task-lifecycle-records-who-made-each-move
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author reviewer page-id rect-id branch-file-id review]} (setup-review)
          out  (create-task* reviewer {:file-id branch-file-id
                                       :page-id page-id
                                       :shape-id rect-id
                                       :assignee-id (:id author)
                                       :pull-request-id (:id review)})
          task (:result out)]
      (t/is (nil? (:error out)))
      (t/is (= "open" (:status task)))
      (t/is (= "Checkout redesign" (:review-title task)))

      (t/testing "the assignee marks it done and the reviewer verifies it"
        (t/is (nil? (:error (set-status* author (:id task) "done"))))
        (t/is (nil? (:error (set-status* reviewer (:id task) "verified")))))

      (t/testing "every move is an event with who made it"
        (let [events (:result (th/command! {::th/type :get-task-events
                                            ::rpc/profile-id (:id author)
                                            :id (:id task)}))]
          (t/is (= ["created" "assigned" "status" "status"] (mapv :type events)))
          (t/is (= [(:id reviewer) (:id reviewer) (:id author) (:id reviewer)]
                   (mapv :profile-id events)))
          (t/is (= {:from "done" :to "verified"} (:payload (last events))))))

      (t/testing "the review counts its tasks"
        (let [row (:result (th/command! {::th/type :get-pull-request
                                         ::rpc/profile-id (:id author)
                                         :id (:id review)}))]
          (t/is (= {"verified" 1} (-> row :counts :tasks))))))))

(t/deftest task-refuses-a-move-its-state-does-not-allow
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          task (:result (create-task* author {:file-id branch-file-id :page-id page-id}))
          out  (set-status* author (:id task) "verified")]
      (t/is (= :invalid-task-transition (-> out :error ex-data :code))))))

(t/deftest task-assignee-must-be-a-team-member
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          stranger (th/create-profile* 3 {:is-active true})
          out      (create-task* author {:file-id branch-file-id
                                         :page-id page-id
                                         :assignee-id (:id stranger)})]
      (t/is (= :assignee-not-team-member (-> out :error ex-data :code))))))

(t/deftest deleted-layer-moves-its-task-to-the-board
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id]} (setup-review)
          board-id (uuid/random)
          task     (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :shape-id rect-id}))
          out      (th/command! {::th/type :mark-tasks-layer-deleted
                                 ::rpc/profile-id (:id author)
                                 :file-id branch-file-id
                                 :layers [{:shape-id rect-id
                                           :shape-name "Pay button"
                                           :frame-id board-id
                                           :frame-name "Checkout / Mobile"
                                           :objects [{:id rect-id :name "Pay button" :type :rect}]}]})]
      (t/is (nil? (:error out)))
      (t/is (= 1 (count (:result out))))
      (let [row (th/db-get :file-task {:id (:id task)})]
        (t/is (= "Pay button" (:lost-shape-name row)))
        (t/is (= board-id (:frame-id row))))

      (t/testing "restoring the layer clears the loss"
        (th/command! {::th/type :mark-task-layer-restored
                      ::rpc/profile-id (:id author)
                      :id (:id task)
                      :frame-id uuid/zero})
        (t/is (nil? (:lost-shape-name (th/db-get :file-task {:id (:id task)}))))))))

(t/deftest review-thread-points-at-a-layer
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [reviewer page-id rect-id branch-file-id review]} (setup-review)
          out (th/command! {::th/type :create-comment-thread
                            ::rpc/profile-id (:id reviewer)
                            :file-id branch-file-id
                            :page-id page-id
                            :frame-id uuid/zero
                            :position (gpt/point 10 20)
                            :content "The pay button loses contrast"
                            :pull-request-id (:id review)
                            :anchor {:type :layer :shape-id rect-id}})]
      (t/is (nil? (:error out)))
      (let [[thread] (:result (th/command! {::th/type :get-comment-threads
                                            ::rpc/profile-id (:id reviewer)
                                            :file-id branch-file-id}))]
        (t/is (= (:id review) (:pull-request-id thread)))
        (t/is (= "Checkout redesign" (:pull-request-title thread)))
        (t/is (= :layer (:anchor-type thread)))
        (t/is (= rect-id (:shape-id thread)))))))

(t/deftest review-thread-refuses-a-review-of-another-file
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [reviewer page-id file review]} (setup-review)
          out (th/command! {::th/type :create-comment-thread
                            ::rpc/profile-id (:id reviewer)
                            :file-id (:id file)
                            :page-id page-id
                            :frame-id uuid/zero
                            :position (gpt/point 0)
                            :content "on main"
                            :pull-request-id (:id review)})]
      (t/is (= :pull-request-not-open (-> out :error ex-data :code))))))

(t/deftest branch-save-under-review-records-its-activity
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id review]} (setup-review)]
      (update-file* author branch-file-id
                    [{:type :mod-obj :page-id page-id :id rect-id
                      :operations [{:type :set :attr :name :val "Pay"}]}])
      (let [[row :as rows] (:result (th/command! {::th/type :get-pull-request-activity
                                                  ::rpc/profile-id (:id author)
                                                  :id (:id review)}))]
        (t/is (= 1 (count rows)))
        (t/is (= (:id author) (:profile-id row)))
        (t/is (= [{:id rect-id :kind :modified :attrs {:name ["Pay button" "Pay"]}}]
                 (mapv #(select-keys % [:id :kind :attrs]) (:entries row))))))))

(t/deftest merge-moves-the-tasks-and-their-threads-into-main
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author page-id rect-id file branch branch-file-id review]} (setup-review)
          ;; main has a thread of its own already: the moved one must not
          ;; take its number
          _      (th/command! {::th/type :create-comment-thread
                               ::rpc/profile-id (:id author)
                               :file-id (:id file)
                               :page-id page-id
                               :frame-id uuid/zero
                               :position (gpt/point 0)
                               :content "on main"})
          thread (:result (th/command! {::th/type :create-comment-thread
                                        ::rpc/profile-id (:id author)
                                        :file-id branch-file-id
                                        :page-id page-id
                                        :frame-id uuid/zero
                                        :position (gpt/point 0)
                                        :content "fix the radius"
                                        :pull-request-id (:id review)}))
          open   (:result (create-task* author {:file-id branch-file-id
                                                :page-id page-id
                                                :shape-id rect-id
                                                :thread-id (:id thread)
                                                :pull-request-id (:id review)}))
          closed (:result (create-task* author {:file-id branch-file-id
                                                :page-id page-id
                                                :title "Drop the promo"}))]
      (set-status* author (:id closed) "wont-do")
      (update-file* author branch-file-id
                    [{:type :mod-obj :page-id page-id :id rect-id
                      :operations [{:type :set :attr :name :val "Pay"}]}])

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id author)
                              :branch-id (:id branch)
                              :notify-assignees true})]
        (t/is (nil? (:error out))))

      (let [tasks (:result (th/command! {::th/type :get-file-tasks
                                         ::rpc/profile-id (:id author)
                                         :file-id (:id file)}))
            [merge-row] (th/db-query :file-branch-merge {:file-id (:id file)})]
        (t/is (= #{(:id open) (:id closed)} (into #{} (map :id) tasks)))
        (t/is (every? #(= (:id merge-row) (:merge-id %)) tasks))
        (t/is (every? #(= "feature-login" (:branch-name %)) tasks))
        (t/is (= "Checkout redesign"
                 (:review-title (th/db-get :file-task {:id (:id open)})))))

      (t/testing "the threads move with their tasks and take the next numbers of main"
        (let [moved  (th/db-get :comment-thread {:id (:id thread)})
              closed (th/db-get :comment-thread {:id (:thread-id closed)})]
          (t/is (= (:id file) (:file-id moved)))
          (t/is (= (:id file) (:file-id closed)))
          (t/is (= [2 3] [(:seqn moved) (:seqn closed)]))
          (t/is (= 3 (:comment-thread-seqn (th/db-get :file {:id (:id file)}
                                                      {::db/remove-deleted false})))))))))

(t/deftest each-message-of-a-thread-may-be-a-task
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          thread   (:result (th/command! {::th/type :create-comment-thread
                                          ::rpc/profile-id (:id author)
                                          :file-id branch-file-id
                                          :page-id page-id
                                          :frame-id uuid/zero
                                          :position (gpt/point 0)
                                          :content "fix the radius"}))
          reply    (:result (th/command! {::th/type :create-comment
                                          ::rpc/profile-id (:id author)
                                          :thread-id (:id thread)
                                          :content "and the shadow"}))
          messages (:result (th/command! {::th/type :get-comments
                                          ::rpc/profile-id (:id author)
                                          :thread-id (:id thread)}))
          first-id (:id (first (sort-by :created-at messages)))
          from     (fn [comment-id]
                     (create-task* author {:file-id branch-file-id
                                           :page-id page-id
                                           :thread-id (:id thread)
                                           :comment-id comment-id}))]

      (t/testing "a thread holds a task per message"
        (t/is (nil? (:error (from first-id))))
        (t/is (nil? (:error (from (:id reply)))))
        (let [tasks (:result (th/command! {::th/type :get-file-tasks
                                           ::rpc/profile-id (:id author)
                                           :file-id branch-file-id}))]
          (t/is (= #{first-id (:id reply)} (into #{} (map :comment-id) tasks)))))

      (t/testing "a message holds one task"
        (t/is (= :comment-already-has-task (-> (from first-id) :error ex-data :code))))

      (t/testing "a message of another thread is refused"
        (let [other (:result (th/command! {::th/type :create-comment-thread
                                           ::rpc/profile-id (:id author)
                                           :file-id branch-file-id
                                           :page-id page-id
                                           :frame-id uuid/zero
                                           :position (gpt/point 0)
                                           :content "another one"}))
              out   (create-task* author {:file-id branch-file-id
                                          :page-id page-id
                                          :thread-id (:id other)
                                          :comment-id (:id reply)})]
          (t/is (= :comment-not-in-thread (-> out :error ex-data :code))))))))

(t/deftest task-on-the-canvas-keeps-its-position
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          out  (create-task* author {:file-id branch-file-id
                                     :page-id page-id
                                     :position (gpt/point 120 340)})
          task (:result out)]
      (t/is (nil? (:error out)))
      (t/is (nil? (:shape-id task)))
      (let [[row] (:result (th/command! {::th/type :get-file-tasks
                                         ::rpc/profile-id (:id author)
                                         :file-id branch-file-id}))]
        (t/is (= {:x 120.0 :y 340.0} (into {} (:position row)))))
      (t/testing "a move of its state answers with the task"
        (t/is (nil? (:error (set-status* author (:id task) "done"))))))))

(t/deftest task-created-on-the-canvas-gets-a-thread
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author reviewer page-id rect-id branch-file-id review]} (setup-review)
          out  (create-task* reviewer {:file-id branch-file-id
                                       :page-id page-id
                                       :shape-id rect-id
                                       :position (gpt/point 30 40)
                                       :title "  Use the secondary token  "
                                       :pull-request-id (:id review)})
          task (:result out)]
      (t/is (nil? (:error out)))
      (t/is (some? (:thread-id task)))
      (t/is (some? (:comment-id task)))

      (t/testing "the thread points at the layer of the task, in its review"
        (let [thread (th/db-get :comment-thread {:id (:thread-id task)})]
          (t/is (= branch-file-id (:file-id thread)))
          (t/is (= (:id reviewer) (:owner-id thread)))
          (t/is (= (:id review) (:pull-request-id thread)))
          (t/is (= "layer" (:anchor-type thread)))
          (t/is (= rect-id (:shape-id thread)))))

      (t/testing "its first message is the title and holds the task"
        (let [[message] (:result (th/command! {::th/type :get-comments
                                               ::rpc/profile-id (:id author)
                                               :thread-id (:thread-id task)}))]
          (t/is (= (:comment-id task) (:id message)))
          (t/is (= "Use the secondary token" (:content message)))))

      (t/testing "anybody can go on commenting in it"
        (let [out (th/command! {::th/type :create-comment
                                ::rpc/profile-id (:id author)
                                :thread-id (:thread-id task)
                                :content "On it"})]
          (t/is (nil? (:error out)))
          (t/is (= 2 (count (th/db-query :comment {:thread-id (:thread-id task)})))))))))

(t/deftest task-on-the-empty-canvas-gets-a-thread-on-its-spot
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          task   (:result (create-task* author {:file-id branch-file-id
                                                :page-id page-id
                                                :position (gpt/point 120 340)}))
          thread (th/db-get :comment-thread {:id (:thread-id task)})]
      (t/is (= "canvas" (:anchor-type thread)))
      (t/is (nil? (:shape-id thread)))
      (t/is (nil? (:pull-request-id thread)))
      (t/is (= {:x 120.0 :y 340.0} (into {} (db/decode-pgpoint (:position thread))))))))

(t/deftest deleting-a-thread-deletes-its-tasks
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch-file-id]} (setup-review)
          task  (:result (create-task* author {:file-id branch-file-id :page-id page-id}))
          other (:result (create-task* author {:file-id branch-file-id
                                               :page-id page-id
                                               :title "Another one"}))
          out   (th/command! {::th/type :delete-comment-thread
                              ::rpc/profile-id (:id author)
                              :id (:thread-id task)})]
      (t/is (nil? (:error out)))
      (t/is (nil? (th/db-get :comment-thread {:id (:thread-id task)})))
      (t/is (some? (:deleted-at (th/db-get :file-task {:id (:id task)}
                                           {::db/remove-deleted false}))))
      (t/is (= [(:id other)]
               (mapv :id (:result (th/command! {::th/type :get-file-tasks
                                                ::rpc/profile-id (:id author)
                                                :file-id branch-file-id}))))))))
