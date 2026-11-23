;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-task-test
  (:require
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.msgbus :as mbus]
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
          review (:result (th/command! {::th/type :create-branch-review
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
                                       :review-id (:id review)})
          task (:result out)]
      (t/is (nil? (:error out)))
      (t/is (= "open" (:status task)))
      (t/is (= "Checkout redesign"
               (->> (th/command! {::th/type :get-file-tasks
                                  ::rpc/profile-id (:id author)
                                  :file-id branch-file-id})
                    :result
                    (filter #(= (:id task) (:id %)))
                    first
                    :review-title)))

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
        (let [row (:result (th/command! {::th/type :get-branch-review
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
                            :review-id (:id review)
                            :anchor {:type :layer :shape-id rect-id}})]
      (t/is (nil? (:error out)))
      (let [[thread] (:result (th/command! {::th/type :get-comment-threads
                                            ::rpc/profile-id (:id reviewer)
                                            :file-id branch-file-id}))]
        (t/is (= (:id review) (:review-id thread)))
        (t/is (= "Checkout redesign" (:review-title thread)))
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
                            :review-id (:id review)})]
      (t/is (= :branch-review-not-open (-> out :error ex-data :code))))))

(t/deftest branch-save-under-review-records-its-activity
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id review]} (setup-review)]
      (update-file* author branch-file-id
                    [{:type :mod-obj :page-id page-id :id rect-id
                      :operations [{:type :set :attr :name :val "Pay"}]}])
      (let [result (:result (th/command! {::th/type :get-branch-review-activity
                                          ::rpc/profile-id (:id author)
                                          :id (:id review)}))]
        (t/is (= [{:id rect-id :kind :modified :attrs {:name ["Pay button" "Pay"]}}]
                 (mapv #(select-keys % [:id :kind :attrs]) (:entries result))))
        (t/is (= (:id author) (-> result :last-save :profile-id)))
        (t/is (= 1 (:people result)))
        (t/is (= 1 (count (:sessions result))))))))

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
                                        :review-id (:id review)}))
          open   (:result (create-task* author {:file-id branch-file-id
                                                :page-id page-id
                                                :shape-id rect-id
                                                :thread-id (:id thread)
                                                :review-id (:id review)}))
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
                 (:review-title (first (filter #(= (:id open) (:id %)) tasks)))))
        (t/is (= (:id review) (:review-id merge-row)))
        (t/is (= "Checkout redesign" (:review-title merge-row)))
        (t/is (= (:family-id (th/db-get :file {:id (:id file)})) (:family-id merge-row))))

      (t/testing "every task gets a `merged` event naming the merge"
        (let [events (:result (th/command! {::th/type :get-task-events
                                            ::rpc/profile-id (:id author)
                                            :id (:id open)}))]
          (t/is (= "merged" (:type (last events))))
          (t/is (= "feature-login" (-> events last :payload :branch-name)))))

      (t/testing "the threads move with their tasks and keep their numbers in the family"
        (let [moved  (th/db-get :comment-thread {:id (:id thread)})
              closed (th/db-get :comment-thread {:id (:thread-id closed)})
              family (:family-id (th/db-get :file {:id (:id file)}))]
          (t/is (= (:id file) (:file-id moved)))
          (t/is (= (:id file) (:file-id closed)))
          (t/is (= [family family] [(:family-id moved) (:family-id closed)]))
          (t/is (= [2 3] [(:seqn moved) (:seqn closed)]))
          (t/is (= 3 (:thread-seqn (th/db-get :file-family {:id family})))))))))

;; --- What the merge moves: the thread is the unit

(defn- thread*
  [profile file-id page-id params]
  (:result (th/command! (merge {::th/type :create-comment-thread
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :page-id page-id
                                :frame-id uuid/zero
                                :position (gpt/point 0)
                                :content "fix the radius"}
                               params))))

(defn- message-ids*
  "The ids of the messages of a thread, oldest first."
  [profile thread-id]
  (->> (th/command! {::th/type :get-comments
                     ::rpc/profile-id (:id profile)
                     :thread-id thread-id})
       :result
       (sort-by :created-at)
       (mapv :id)))

(defn- setup-review-threads
  "The branch of `setup-review` with four threads: a review thread holding
  an open task and a closed one (`mixed`), a review thread whose only task
  is closed (`closed` is that task), a review thread with no task
  (`quiet`), and a thread written outside the review (`loose`)."
  []
  (let [{:keys [author reviewer page-id branch-file-id review] :as setup} (setup-review)
        mixed (thread* reviewer branch-file-id page-id {:review-id (:id review)})
        _     (th/command! {::th/type :create-comment
                            ::rpc/profile-id (:id reviewer)
                            :thread-id (:id mixed)
                            :content "and the shadow"})
        [first-id reply-id] (message-ids* author (:id mixed))
        open   (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :review-id (:id review)
                                              :thread-id (:id mixed)
                                              :comment-id first-id
                                              :assignee-id (:id reviewer)
                                              :title "Fix the radius"}))
        done   (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :review-id (:id review)
                                              :thread-id (:id mixed)
                                              :comment-id reply-id
                                              :title "Fix the shadow"}))
        closed (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :review-id (:id review)
                                              :assignee-id (:id reviewer)
                                              :title "Drop the promo"}))
        quiet  (thread* reviewer branch-file-id page-id {:review-id (:id review)
                                                         :content "Is this button still needed?"})
        loose  (thread* author branch-file-id page-id {:content "a note outside the review"})]
    (set-status* author (:id done) "wont-do")
    (set-status* author (:id closed) "wont-do")
    (assoc setup
           :mixed mixed
           :open open
           :done done
           :closed closed
           :quiet quiet
           :loose loose)))

(defn- merge*
  [profile branch & {:as params}]
  (th/command! (merge {::th/type :merge-file-branch
                       ::rpc/profile-id (:id profile)
                       :branch-id (:id branch)}
                      params)))

(t/deftest merge-moves-the-threads-with-an-open-task-and-those-outside-reviews
  (let [sent (atom [])]
    (with-redefs [cf/flags (task-flags)
                  eml/send-template (fn [_ params] (swap! sent conj params) nil)]
      (let [{:keys [author reviewer file branch branch-file-id review mixed open done closed quiet loose]}
            (setup-review-threads)]
        (reset! sent [])
        (t/is (nil? (:error (merge* author branch :notify-assignees true))))

        (t/testing "a thread holding an open task moves whole, with its closed task"
          (t/is (= (:id file) (:file-id (th/db-get :comment-thread {:id (:id mixed)}))))
          (let [rows (mapv #(th/db-get :file-task {:id (:id %)}) [open done])]
            (t/is (= [(:id file) (:id file)] (mapv :file-id rows)))
            (t/is (every? (comp some? :merge-id) rows))))

        (t/testing "a thread written outside any review moves"
          (t/is (= (:id file) (:file-id (th/db-get :comment-thread {:id (:id loose)})))))

        (t/testing "a review thread with no open task stays on the branch file"
          (let [task (th/db-get :file-task {:id (:id closed)})]
            (t/is (= branch-file-id (:file-id task)))
            (t/is (nil? (:merge-id task)))
            (t/is (= branch-file-id (:file-id (th/db-get :comment-thread {:id (:thread-id closed)}))))))

        (t/testing "a review thread with no task stays on the branch file"
          (t/is (= branch-file-id (:file-id (th/db-get :comment-thread {:id (:id quiet)})))))

        (t/testing "main lists the moved threads and their tasks only"
          (let [threads (:result (th/command! {::th/type :get-comment-threads
                                               ::rpc/profile-id (:id author)
                                               :file-id (:id file)}))
                tasks   (:result (th/command! {::th/type :get-file-tasks
                                               ::rpc/profile-id (:id author)
                                               :file-id (:id file)}))]
            (t/is (= #{(:id mixed) (:id loose)} (into #{} (map :id) threads)))
            (t/is (= #{(:id open) (:id done)} (into #{} (map :id) tasks)))))

        (t/testing "the assignee of the moved open task is told, and nobody else"
          (let [merged (filterv #(= "tasks-merged" (::eml/template %)) @sent)]
            (t/is (= [(:email reviewer)] (mapv :to merged)))
            (t/is (= "- Fix the radius" (:task-titles (first merged))))))

        (t/testing "the review is kept as merged"
          (t/is (= "merged" (:status (th/db-get :file-branch-review {:id (:id review)})))))))))

(defn- move-thread*
  [profile thread-id]
  (th/command! {::th/type :move-branch-review-thread
                ::rpc/profile-id (:id profile)
                :thread-id thread-id}))

(t/deftest a-thread-left-behind-moves-to-the-target-on-request
  (let [sent (atom [])]
    (with-redefs [cf/flags (task-flags)
                  eml/send-template (fn [_ params] (swap! sent conj params) nil)]
      (let [{:keys [author reviewer file branch branch-file-id closed quiet]} (setup-review-threads)
            viewer (th/create-profile* 3 {:is-active true})
            thread-id (:thread-id closed)]
        (th/create-team-role* {:team-id (:default-team-id author)
                               :profile-id (:id viewer)
                               :role :viewer})

        (t/testing "a thread of a branch that is not merged is refused"
          (t/is (= :branch-not-merged (-> (move-thread* author thread-id) :error ex-data :code)))
          (t/is (= branch-file-id (:file-id (th/db-get :comment-thread {:id thread-id})))))

        (t/is (nil? (:error (merge* author branch))))
        (t/is (= branch-file-id (:file-id (th/db-get :comment-thread {:id thread-id}))))

        ;; the task of the thread left behind is open again: no command
        ;; reaches a task on the deleted branch file, so the row is set
        ;; directly, to see the move tell its assignee
        (th/db-exec! ["UPDATE file_task SET status = 'reopened' WHERE id = ?" (:id closed)])

        (t/testing "a viewer of the target may not move it"
          (t/is (= :not-found (-> (move-thread* viewer thread-id) :error ex-data :type)))
          (t/is (= branch-file-id (:file-id (th/db-get :comment-thread {:id thread-id})))))

        (reset! sent [])

        (t/testing "an editor of the target moves it whole, with its task"
          (let [out  (move-thread* author thread-id)
                task (th/db-get :file-task {:id (:id closed)})
                [merge-row] (th/db-query :file-branch-merge {:file-id (:id file)})]
            (t/is (nil? (:error out)))
            (t/is (= [(:id closed)] (-> out :result :task-ids)))
            (t/is (= (:id file) (:file-id (th/db-get :comment-thread {:id thread-id}))))
            (t/is (= (:id file) (:file-id task)))
            (t/is (= (:id merge-row) (:merge-id task)))
            (let [event (last (:result (th/command! {::th/type :get-task-events
                                                     ::rpc/profile-id (:id author)
                                                     :id (:id closed)})))]
              (t/is (= "merged" (:type event)))
              (t/is (= "feature-login" (-> event :payload :branch-name))))))

        (t/testing "the assignee of the moved open task is told, the mover not"
          (let [merged (filterv #(= "tasks-merged" (::eml/template %)) @sent)]
            (t/is (= [(:email reviewer)] (mapv :to merged)))
            (t/is (= "- Drop the promo" (:task-titles (first merged))))))

        (t/testing "the moved thread reads on the target, the other one still not"
          (let [threads (:result (th/command! {::th/type :get-comment-threads
                                               ::rpc/profile-id (:id author)
                                               :file-id (:id file)}))
                ids     (into #{} (map :id) threads)]
            (t/is (contains? ids thread-id))
            (t/is (not (contains? ids (:id quiet))))))

        (t/testing "a thread already on the target is refused"
          (t/is (= :thread-already-on-target
                   (-> (move-thread* author thread-id) :error ex-data :code))))))))

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
                                       :review-id (:id review)})
          task (:result out)]
      (t/is (nil? (:error out)))
      (t/is (some? (:thread-id task)))
      (t/is (some? (:comment-id task)))

      (t/testing "the thread points at the layer of the task, in its review"
        (let [thread (th/db-get :comment-thread {:id (:thread-id task)})]
          (t/is (= branch-file-id (:file-id thread)))
          (t/is (= (:id reviewer) (:owner-id thread)))
          (t/is (= (:id review) (:review-id thread)))
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
      (t/is (= "point" (:anchor-type thread)))
      (t/is (nil? (:shape-id thread)))
      (t/is (nil? (:review-id thread)))
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

;; --- Messages to the open clients

(defn- capture-messages
  "Run `f` and return the msgbus messages it published."
  [f]
  (let [seen (atom [])]
    (with-redefs [mbus/pub! (fn [_ & {:keys [topic message]}]
                              (swap! seen conj {:topic topic :message message}))]
      (f))
    @seen))

(defn- tasks-change-of
  [messages topic]
  (some #(when (and (= topic (:topic %))
                    (= :file-tasks-change (-> % :message :type)))
           (:message %))
        messages))

(t/deftest task-change-messages-name-the-session-and-the-task
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id]} (setup-review)
          session-id (uuid/random)
          task       (atom nil)
          messages   (capture-messages
                      #(reset! task (:result (create-task* author {:file-id branch-file-id
                                                                   :page-id page-id
                                                                   :shape-id rect-id
                                                                   ::rpc/session-id session-id}))))
          message    (tasks-change-of messages branch-file-id)]
      (t/is (= session-id (:session-id message)))
      (t/is (= [(:id @task)] (:task-ids message)))
      (t/is (= [(:title @task)] (mapv :title (:tasks message))))

      (t/testing "a deleted task is named without a row"
        (let [message (-> (capture-messages
                           #(th/command! {::th/type :delete-task
                                          ::rpc/profile-id (:id author)
                                          :id (:id @task)}))
                          (tasks-change-of branch-file-id))]
          (t/is (= [(:id @task)] (:task-ids message)))
          (t/is (empty? (:tasks message))))))))

(t/deftest the-merge-tells-main-that-its-tasks-changed
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author page-id rect-id file branch branch-file-id]} (setup-review)
          task     (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :shape-id rect-id}))
          _        (update-file* author branch-file-id
                                 [{:type :mod-obj :page-id page-id :id rect-id
                                   :operations [{:type :set :attr :name :val "Pay"}]}])
          messages (capture-messages
                    #(t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                                       ::rpc/profile-id (:id author)
                                                       :branch-id (:id branch)})))))
          message  (tasks-change-of messages (:id file))]
      (t/is (some? message))
      (t/is (= [(:id task)] (:task-ids message)))
      (t/is (= [(:id file)] (mapv :file-id (:tasks message)))))))

;; --- Creating a task while the branch merges

(t/deftest a-task-created-during-the-merge-does-not-stay-on-the-branch
  ;; the merge reads the tasks of the branch, then deletes the branch
  ;; file: a task created in between must not be left on the deleted
  ;; file. The hook runs another session's creation once the merge has
  ;; moved the tasks, and waits for it, but not past the lock the merge
  ;; holds.
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author page-id rect-id branch branch-file-id]} (setup-review)
          orig-update db/update!
          created     (atom nil)]
      (update-file* author branch-file-id
                    [{:type :mod-obj :page-id page-id :id rect-id
                      :operations [{:type :set :attr :name :val "Pay"}]}])
      (with-redefs [db/update!
                    (fn [conn table params & more]
                      (when (and (= :file-branch table)
                                 (= "merged" (:status params))
                                 (compare-and-set! created nil ::started))
                        (let [creation (future (create-task* author {:file-id branch-file-id
                                                                     :page-id page-id
                                                                     :shape-id rect-id
                                                                     :title "Late task"}))]
                          (reset! created creation)
                          (deref creation 2000 nil)))
                      (apply orig-update conn table params more))]
        (t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                          ::rpc/profile-id (:id author)
                                          :branch-id (:id branch)})))))
      (let [out (deref @created 10000 ::timeout)]
        (t/is (= :object-not-found (-> out :error ex-data :code))))
      (t/is (empty? (th/db-exec! ["SELECT id FROM file_task WHERE file_id = ?" branch-file-id]))))))

;; --- Where a carried task lands when the merge leaves its layer out

(defn- add-shape*
  [profile file-id page-id {:keys [id parent-id frame-id] :as attrs}]
  (update-file* profile file-id
                [{:type :add-obj
                  :page-id page-id
                  :id id
                  :parent-id parent-id
                  :frame-id frame-id
                  :obj (cts/setup-shape attrs)}]))

(defn- setup-board-branch
  "A file whose main holds a board with a rect inside, and a branch of
  it. Returns the ids the tests need."
  []
  (let [author   (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
        page-id  (page-id-of author (:id file))
        board-id (uuid/random)
        rect-id  (uuid/random)]
    (add-shape* author (:id file) page-id
                {:id board-id :name "Checkout" :type :frame
                 :x 0 :y 0 :width 400 :height 800
                 :parent-id uuid/zero :frame-id uuid/zero})
    (add-shape* author (:id file) page-id
                {:id rect-id :name "Pay button" :type :rect
                 :x 20 :y 600 :width 300 :height 48
                 :parent-id board-id :frame-id board-id})
    (let [branch (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id author)
                                        :file-id (:id file)
                                        :name "feature-login"}))]
      {:author author
       :file file
       :page-id page-id
       :board-id board-id
       :rect-id rect-id
       :branch branch
       :branch-file-id (:branch-file-id branch)})))

(defn- add-promo*
  "Add on the branch a board with a rect inside, which main lacks.
  Returns their ids."
  [author branch-file-id page-id]
  (let [promo-id (uuid/random)
        badge-id (uuid/random)]
    (add-shape* author branch-file-id page-id
                {:id promo-id :name "Promo" :type :frame
                 :x 500 :y 0 :width 400 :height 800
                 :parent-id uuid/zero :frame-id uuid/zero})
    (add-shape* author branch-file-id page-id
                {:id badge-id :name "Badge" :type :rect
                 :x 520 :y 600 :width 300 :height 48
                 :parent-id promo-id :frame-id promo-id})
    {:promo-id promo-id :badge-id badge-id}))

(defn- merge-excluding*
  [profile branch page-id shape-ids]
  (th/command! {::th/type :merge-file-branch
                ::rpc/profile-id (:id profile)
                :branch-id (:id branch)
                :excluded (into #{}
                                (map #(bm/change-key {:kind :shape :page-id page-id :id %}))
                                shape-ids)}))

(defn- task-events*
  [profile task-id]
  (:result (th/command! {::th/type :get-task-events
                         ::rpc/profile-id (:id profile)
                         :id task-id})))

(t/deftest a-carried-task-lands-on-the-layer-its-parent-has-on-main
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author file page-id board-id rect-id branch branch-file-id]} (setup-board-branch)
          task (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :frame-id board-id
                                              :shape-id rect-id}))]
      ;; the branch deletes the rect: the merge does not carry it
      (update-file* author branch-file-id [{:type :del-obj :page-id page-id :id rect-id}])
      (t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                        ::rpc/profile-id (:id author)
                                        :branch-id (:id branch)}))))
      (let [row    (th/db-get :file-task {:id (:id task)})
            thread (th/db-get :comment-thread {:id (:thread-id task)})]
        (t/is (= (:id file) (:file-id row)))
        (t/is (= board-id (:shape-id row)))
        (t/is (= board-id (:frame-id row)))
        (t/is (nil? (:lost-shape-name row)))
        (t/testing "the thread created with the task follows it"
          (t/is (= "layer" (:anchor-type thread)))
          (t/is (= board-id (:shape-id thread)))))
      (t/testing "the landing is an event naming the layer"
        (let [event (last (task-events* author (:id task)))]
          (t/is (= "relinked" (:type event)))
          (t/is (= {:shape-id board-id :shape-name "Checkout"} (:payload event))))))))

(t/deftest the-merge-parent-fallback-uses-the-branch-parent
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id branch branch-file-id]} (setup-board-branch)
          {:keys [promo-id badge-id]} (add-promo* author branch-file-id page-id)
          task (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :frame-id promo-id
                                              :shape-id badge-id}))]
      ;; the merge carries the new board and leaves the rect out: the rect
      ;; exists on the branch only, so its parent comes from the branch
      (t/is (nil? (:error (merge-excluding* author branch page-id [badge-id]))))
      (let [row (th/db-get :file-task {:id (:id task)})]
        (t/is (= promo-id (:shape-id row)))
        (t/is (= promo-id (:frame-id row)))))))

(t/deftest a-carried-task-with-nothing-left-lands-on-an-area
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id board-id branch branch-file-id]} (setup-board-branch)
          {:keys [promo-id badge-id]} (add-promo* author branch-file-id page-id)
          task (:result (create-task* author {:file-id branch-file-id
                                              :page-id page-id
                                              :frame-id promo-id
                                              :shape-id badge-id}))]
      ;; something else merges: the board of main is renamed
      (update-file* author branch-file-id
                    [{:type :mod-obj :page-id page-id :id board-id
                      :operations [{:type :set :attr :name :val "Checkout v2"}]}])
      ;; leaving the new board out leaves its rect out with it
      (t/is (nil? (:error (merge-excluding* author branch page-id [promo-id]))))
      (let [row    (th/db-get :file-task {:id (:id task)})
            thread (th/db-get :comment-thread {:id (:thread-id task)})
            area   (db/decode-transit-pgobject (:area thread))]
        (t/is (nil? (:shape-id row)))
        (t/is (= uuid/zero (:frame-id row)))
        ;; the mark sits on the top right corner of the lost layer
        (t/is (= {:x 820.0 :y 600.0} (into {} (db/decode-pgpoint (:position row)))))
        (t/is (= "area" (:anchor-type thread)))
        (t/is (nil? (:shape-id thread)))
        (t/is (= [520.0 600.0 300.0 48.0]
                 (mapv #(double (get area %)) [:x :y :width :height]))))
      (let [event (last (task-events* author (:id task)))]
        (t/is (= "relinked" (:type event)))
        (t/is (some? (-> event :payload :area)))))))

(t/deftest a-moved-review-thread-whose-layer-the-branch-deleted-lands-on-its-parent
  ;; The thread is the unit: a review thread holding an open task moves
  ;; with the merge, and when the branch deleted the layer it points at,
  ;; the thread itself lands on the parent of that layer, as its task does.
  (with-redefs [cf/flags (task-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [author file page-id board-id rect-id branch branch-file-id]} (setup-board-branch)
          reviewer (th/create-profile* 2 {:is-active true})
          _        (th/create-team-role* {:team-id (:default-team-id author)
                                          :profile-id (:id reviewer)
                                          :role :editor})
          review   (:result (th/command! {::th/type :create-branch-review
                                          ::rpc/profile-id (:id author)
                                          :branch-id (:id branch)
                                          :title "Checkout redesign"
                                          :reviewers [(:id reviewer)]}))
          thread   (thread* reviewer branch-file-id page-id
                            {:frame-id board-id
                             :review-id (:id review)
                             :anchor {:type :layer :shape-id rect-id}
                             :content "Is this button still needed?"})
          task     (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :frame-id board-id
                                                  :shape-id rect-id
                                                  :review-id (:id review)
                                                  :thread-id (:id thread)
                                                  :comment-id (first (message-ids* author (:id thread)))}))]
      (t/is (some? (:id task)))
      (t/is (nil? (:error (update-file* author branch-file-id
                                        [{:type :del-obj :page-id page-id :id rect-id}]))))
      (t/is (nil? (:error (merge* author branch))))

      (let [row (th/db-get :comment-thread {:id (:id thread)})]
        (t/is (= (:id file) (:file-id row)))
        (t/is (= (:id review) (:review-id row)))
        (t/is (= "layer" (:anchor-type row)))
        (t/is (= board-id (:shape-id row)))
        (t/is (= board-id (:frame-id row))))
      (let [row (th/db-get :file-task {:id (:id task)})]
        (t/is (= (:id file) (:file-id row)))
        (t/is (= board-id (:shape-id row)))))))

;; --- Deleted layers of any size

(t/deftest a-task-on-a-board-larger-than-the-snapshot-cap-is-still-marked-lost
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id]} (setup-review)
          small-id (uuid/random)
          bogus-id (uuid/random)
          create   (fn [shape-id]
                     (:result (create-task* author {:file-id branch-file-id
                                                    :page-id page-id
                                                    :shape-id shape-id})))
          large    (create rect-id)
          small    (create small-id)
          bogus    (create bogus-id)
          children (map (fn [_]
                          (cts/setup-shape {:id (uuid/random) :name "Item" :type :rect
                                            :parent-id rect-id :frame-id rect-id}))
                        (range 501))
          out      (th/command! {::th/type :mark-tasks-layer-deleted
                                 ::rpc/profile-id (:id author)
                                 :file-id branch-file-id
                                 :layers [{:shape-id rect-id
                                           :shape-name "Pay button"
                                           :frame-id uuid/zero
                                           :objects (into [(cts/setup-shape
                                                            {:id rect-id :name "Pay button" :type :frame
                                                             :parent-id uuid/zero :frame-id uuid/zero})]
                                                          children)}
                                          {:shape-id small-id
                                           :shape-name "Badge"
                                           :frame-id uuid/zero
                                           :objects [(cts/setup-shape
                                                      {:id small-id :name "Badge" :type :rect
                                                       :parent-id uuid/zero :frame-id uuid/zero})]}
                                          ;; not a shape: no restore may make it a layer
                                          {:shape-id bogus-id
                                           :shape-name "Bogus"
                                           :frame-id uuid/zero
                                           :objects [{:id bogus-id :type :rect}]}]})
          payload  (fn [task]
                     (->> (task-events* author (:id task))
                          (filter #(= "layer-deleted" (:type %)))
                          (last)
                          :payload))]
      (t/is (nil? (:error out)))
      (t/is (= 3 (count (:result out))))
      (t/testing "every task is lost"
        (t/is (= "Pay button" (:lost-shape-name (th/db-get :file-task {:id (:id large)}))))
        (t/is (= "Badge" (:lost-shape-name (th/db-get :file-task {:id (:id small)}))))
        (t/is (= "Bogus" (:lost-shape-name (th/db-get :file-task {:id (:id bogus)})))))
      (t/testing "the event keeps the layer only when small and valid"
        (t/is (not (contains? (payload large) :objects)))
        (t/is (= [small-id] (mapv :id (:objects (payload small)))))
        (t/is (not (contains? (payload bogus) :objects)))))))

;; --- Deleting a task

(t/deftest only-the-author-may-delete-a-task-and-it-leaves-an-event
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author reviewer page-id branch-file-id]} (setup-review)
          task   (:result (create-task* author {:file-id branch-file-id :page-id page-id}))
          delete (fn [profile]
                   (th/command! {::th/type :delete-task
                                 ::rpc/profile-id (:id profile)
                                 :id (:id task)}))]
      (t/testing "a reviewer cannot delete it"
        (t/is (= :not-allowed (-> (delete reviewer) :error ex-data :code)))
        (t/is (some? (th/db-get :file-task {:id (:id task)}))))

      (t/testing "its author can, and the deletion is an event"
        (t/is (nil? (:error (delete author))))
        (t/is (some? (:deleted-at (th/db-get :file-task {:id (:id task)}
                                             {::db/remove-deleted false}))))
        (let [rows (th/db-exec! ["SELECT profile_id FROM file_task_event
                                   WHERE task_id = ? AND type = 'deleted'"
                                 (:id task)])]
          (t/is (= [(:id author)] (mapv :profile-id rows))))))))

(t/deftest a-team-owner-or-admin-may-delete-the-task-of-somebody-else
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author reviewer page-id branch-file-id]} (setup-review)
          admin  (th/create-profile* 3 {:is-active true})
          _      (th/create-team-role* {:team-id (:default-team-id author)
                                        :profile-id (:id admin)
                                        :role :admin})
          task*  (fn [profile]
                   (:result (create-task* profile {:file-id branch-file-id :page-id page-id})))
          delete (fn [profile task]
                   (th/command! {::th/type :delete-task
                                 ::rpc/profile-id (:id profile)
                                 :id (:id task)}))
          actor  (fn [task]
                   (->> (th/db-exec! ["SELECT profile_id FROM file_task_event
                                        WHERE task_id = ? AND type = 'deleted'"
                                      (:id task)])
                        (mapv :profile-id)))]

      (t/testing "the owner of the team may delete the task of an editor"
        (let [task (task* reviewer)]
          (t/is (nil? (:error (delete author task))))
          (t/is (= [(:id author)] (actor task)))))

      (t/testing "an admin of the team may delete the task of somebody else"
        (let [task (task* reviewer)]
          (t/is (nil? (:error (delete admin task))))
          (t/is (= [(:id admin)] (actor task)))))

      (t/testing "an editor who is not the author may not"
        (let [task (task* author)]
          (t/is (= :not-allowed (-> (delete reviewer task) :error ex-data :code)))
          (t/is (nil? (:deleted-at (th/db-get :file-task {:id (:id task)})))))))))

;; --- Relinking a task

(t/deftest relinking-a-task-moves-the-thread-it-was-created-with
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id]} (setup-review)
          other-id (uuid/random)
          relink   (fn [task]
                     (th/command! {::th/type :relink-task
                                   ::rpc/profile-id (:id author)
                                   :id (:id task)
                                   :page-id page-id
                                   :frame-id uuid/zero
                                   :shape-id other-id
                                   :shape-name "Promo"}))]
      (add-rect* author branch-file-id page-id other-id "Promo")

      (t/testing "a task created on a layer takes its thread along"
        (let [task (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :shape-id rect-id}))]
          (t/is (nil? (:error (relink task))))
          (let [thread (th/db-get :comment-thread {:id (:thread-id task)})]
            (t/is (= other-id (:shape-id thread)))
            (t/is (= "layer" (:anchor-type thread))))))

      (t/testing "a task made from a reply leaves the thread where it is"
        (let [thread (:result (th/command! {::th/type :create-comment-thread
                                            ::rpc/profile-id (:id author)
                                            :file-id branch-file-id
                                            :page-id page-id
                                            :frame-id uuid/zero
                                            :position (gpt/point 0)
                                            :content "the radius is off"
                                            :anchor {:type :layer :shape-id rect-id}}))
              reply  (:result (th/command! {::th/type :create-comment
                                            ::rpc/profile-id (:id author)
                                            :thread-id (:id thread)
                                            :content "and the shadow"}))
              task   (:result (create-task* author {:file-id branch-file-id
                                                    :page-id page-id
                                                    :shape-id rect-id
                                                    :thread-id (:id thread)
                                                    :comment-id (:id reply)}))]
          (t/is (nil? (:error (relink task))))
          (t/is (= rect-id (:shape-id (th/db-get :comment-thread {:id (:id thread)}))))
          (t/is (= other-id (:shape-id (th/db-get :file-task {:id (:id task)})))))))))

(t/deftest relinking-a-task-made-from-the-first-message-leaves-its-thread
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author page-id rect-id branch-file-id]} (setup-review)
          other-id (uuid/random)
          thread   (:result (th/command! {::th/type :create-comment-thread
                                          ::rpc/profile-id (:id author)
                                          :file-id branch-file-id
                                          :page-id page-id
                                          :frame-id uuid/zero
                                          :position (gpt/point 0)
                                          :content "the radius is off"
                                          :anchor {:type :layer :shape-id rect-id}}))
          task     (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :shape-id rect-id
                                                  :thread-id (:id thread)
                                                  :comment-id (:comment-id thread)}))]
      (add-rect* author branch-file-id page-id other-id "Promo")
      (t/is (false? (:created-thread (th/db-get :file-task {:id (:id task)}))))
      (t/is (nil? (:error (th/command! {::th/type :relink-task
                                        ::rpc/profile-id (:id author)
                                        :id (:id task)
                                        :page-id page-id
                                        :frame-id uuid/zero
                                        :shape-id other-id
                                        :shape-name "Promo"}))))
      (t/is (= rect-id (:shape-id (th/db-get :comment-thread {:id (:id thread)}))))
      (t/is (= other-id (:shape-id (th/db-get :file-task {:id (:id task)})))))))

;; --- Renaming a task

(defn- messages-of
  [profile thread-id]
  (mapv :content (:result (th/command! {::th/type :get-comments
                                        ::rpc/profile-id (:id profile)
                                        :thread-id thread-id}))))

(defn- renamed-titles
  [profile task]
  (->> (:result (th/command! {::th/type :get-task-events
                              ::rpc/profile-id (:id profile)
                              :id (:id task)}))
       (filter #(= "renamed" (:type %)))
       (mapv #(-> % :payload :title))))

(t/deftest renaming-a-task-rewrites-its-message-only-for-the-one-who-wrote-it
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author reviewer page-id branch-file-id]} (setup-review)
          rename (fn [profile task title]
                   (th/command! {::th/type :update-task
                                 ::rpc/profile-id (:id profile)
                                 :id (:id task)
                                 :title title}))]

      (t/testing "the author renames the message of a task created on the canvas"
        (let [task (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :title "first title"}))]
          (t/is (nil? (:error (rename author task "second title"))))
          (t/is (= "second title" (:title (th/db-get :file-task {:id (:id task)}))))
          (t/is (= ["second title"] (messages-of author (:thread-id task))))
          (t/is (= ["second title"] (renamed-titles author task)))))

      (t/testing "somebody else renames the task but not its message"
        (let [task (:result (create-task* author {:file-id branch-file-id
                                                  :page-id page-id
                                                  :title "the author's words"}))]
          (t/is (nil? (:error (rename reviewer task "the reviewer's words"))))
          (t/is (= "the reviewer's words" (:title (th/db-get :file-task {:id (:id task)}))))
          (t/is (= ["the author's words"] (messages-of author (:thread-id task))))
          (t/is (= ["the reviewer's words"] (renamed-titles author task)))))

      (t/testing "the message of an existing thread stays whatever the title"
        (let [thread (:result (th/command! {::th/type :create-comment-thread
                                            ::rpc/profile-id (:id author)
                                            :file-id branch-file-id
                                            :page-id page-id
                                            :frame-id uuid/zero
                                            :position (gpt/point 0)
                                            :content "the radius is off"}))
              task   (:result (create-task* author {:file-id branch-file-id
                                                    :page-id page-id
                                                    :thread-id (:id thread)
                                                    :comment-id (:comment-id thread)}))]
          (t/is (false? (:created-thread (th/db-get :file-task {:id (:id task)}))))
          (t/is (nil? (:error (rename author task "a better title"))))
          (t/is (= ["the radius is off"] (messages-of author (:id thread))))
          (t/is (= ["a better title"] (renamed-titles author task))))))))

;; --- Emails of a move

(t/deftest a-move-tells-the-person-it-concerns-and-nobody-else
  (with-redefs [cf/flags (task-flags)]
    (let [{:keys [author reviewer page-id branch-file-id]} (setup-review)
          sent (atom [])]
      (with-redefs [eml/send-template
                    (fn [_ params]
                      (swap! sent conj {:template (::eml/template params)
                                        :to (:to params)
                                        :task-title (:task-title params)}))]
        (let [new-task (fn []
                         (:result (create-task* author {:file-id branch-file-id
                                                        :page-id page-id
                                                        :assignee-id (:id reviewer)})))
              move (fn [profile task status]
                     (reset! sent [])
                     (let [out (set-status* profile (:id task) status)]
                       (t/is (nil? (:error out)))
                       @sent))]

          (t/testing "done tells the creator"
            (let [task (new-task)]
              (t/is (= [{:template "task-done"
                         :to (:email author)
                         :task-title (:title task)}]
                       (move reviewer task "done")))))

          (t/testing "a move that concerns only the actor tells nobody"
            (let [task (new-task)]
              (t/is (= [] (move author task "done")))))

          (t/testing "reopened tells the assignee"
            (let [task (new-task)]
              (move reviewer task "done")
              (t/is (= [{:template "task-reopened"
                         :to (:email reviewer)
                         :task-title (:title task)}]
                       (move author task "reopened")))))

          (t/testing "verified tells nobody"
            (let [task (new-task)]
              (move reviewer task "done")
              (t/is (= [] (move author task "verified")))))

          (t/testing "a profile that refuses the task emails is told nothing"
            (th/db-update! :profile
                           {:props (db/tjson {:notifications {:email-tasks :none}})}
                           {:id (:id author)})
            (let [task (new-task)]
              (t/is (= [] (move reviewer task "done"))))))))))
