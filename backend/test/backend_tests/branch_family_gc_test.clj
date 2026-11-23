;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-family-gc-test
  "The objects GC deletes a branch's file row, and what the branch owned
  belongs to its file family: the review with its verdicts and activity,
  the review threads the merge left on the branch with their tasks, the
  branch edge that names it, and the compare marks of the branch. A merge
  moves the threads holding an open task to the target; the other review
  threads stay with the branch and outlive its file, readable through
  their review; a branch no merge accepted takes them with it. A deleted
  file reaches every branch cut from it, however deep the chain."
  (:require
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- flags
  "The test flags plus `:branching`, read at run time, inside the
  `th/state-init` fixture, so the flags it turns off stay off: a value
  captured when the namespace loads would carry the production defaults
  instead."
  []
  (conj cf/flags :branching))

(defn- stack-flags
  []
  (conj (flags) :branch-stacking))

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

(defn- delete-obj*
  [profile file-id page-id shape-id]
  (update-file* profile file-id
                [{:type :del-obj :page-id page-id :id shape-id}]))

(defn- create-branch*
  [profile file-id name]
  (:result (th/command! {::th/type :create-file-branch
                         ::rpc/profile-id (:id profile)
                         :file-id file-id
                         :name name})))

(defn- create-review*
  [profile branch reviewer]
  (:result (th/command! {::th/type :create-branch-review
                         ::rpc/profile-id (:id profile)
                         :branch-id (:id branch)
                         :title "Checkout redesign"
                         :reviewers [(:id reviewer)]})))

(defn- submit-verdict*
  [profile review verdict]
  (th/command! {::th/type :submit-branch-review-verdict
                ::rpc/profile-id (:id profile)
                :id (:id review)
                :verdict verdict
                :comment "the token reads well"}))

(defn- create-thread*
  [profile file-id page-id params]
  (:result (th/command! (merge {::th/type :create-comment-thread
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :page-id page-id
                                :frame-id uuid/zero
                                :position (gpt/point 0)
                                :content "does this keep the token?"}
                               params))))

(defn- threads-of
  [profile file-id]
  (:result (th/command! {::th/type :get-comment-threads
                         ::rpc/profile-id (:id profile)
                         :file-id file-id})))

(defn- review-of
  [profile review-id]
  (:result (th/command! {::th/type :get-branch-review
                         ::rpc/profile-id (:id profile)
                         :id review-id})))

(defn- activity-of
  [profile review-id]
  (:result (th/command! {::th/type :get-branch-review-activity
                         ::rpc/profile-id (:id profile)
                         :id review-id})))

(defn- row
  "A row as it stands after a soft delete, or nil once it is gone."
  [table id]
  (th/db-get table {:id id} {::db/remove-deleted false}))

(defn- run-gc!
  "Run the deletion cascade the commands queued, then collect everything
  it marked, every delay past, as a later GC run would."
  []
  (th/run-pending-jobs)
  (th/run-task! :objects-gc {:skip-delay true}))

(defn- setup-reviewed-branch
  "A file with a layer, a branch of it, and an open review of the branch
  with a reviewer. Returns the ids the tests need; the tests write the
  thread, because each one anchors it differently."
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
                                        :name "review-pay-button"}))
          review (create-review* author branch reviewer)]
      {:author         author
       :reviewer       reviewer
       :file           file
       :page-id        page-id
       :rect-id        rect-id
       :branch         branch
       :branch-file-id (:branch-file-id branch)
       :review         review})))

(t/deftest a-merged-branch-s-thread-and-review-outlive-its-file
  ;; The acceptance case: the review holds a thread with no task, whose
  ;; layer the branch deleted before the merge, and a thread whose only
  ;; task is closed. Neither holds an open task, so the merge leaves both
  ;; on the branch, and deleting the branch file afterwards takes neither
  ;; the threads nor the review with it: they lose their file and read
  ;; through the review.
  (with-redefs [cf/flags (flags)]
    (let [{:keys [author reviewer file page-id rect-id branch branch-file-id review]}
          (setup-reviewed-branch)

          thread (create-thread* author branch-file-id page-id
                                 {:anchor {:type :layer :shape-id rect-id}
                                  :review-id (:id review)})
          task   (:result (th/command! {::th/type :create-task
                                        ::rpc/profile-id (:id reviewer)
                                        :file-id branch-file-id
                                        :page-id page-id
                                        :frame-id uuid/zero
                                        :position (gpt/point 0)
                                        :review-id (:id review)
                                        :title "Drop the promo"}))]

      (t/testing "the threads hang from the open review"
        (t/is (some? (:id review)))
        (t/is (some? (:id thread)))
        (t/is (= (:id review) (:review-id (row :comment-thread (:id thread)))))
        (t/is (= (:id review) (:review-id (row :comment-thread (:thread-id task))))))

      (t/is (nil? (:error (th/command! {::th/type :set-task-status
                                        ::rpc/profile-id (:id author)
                                        :id (:id task)
                                        :status "wont-do"}))))

      (t/testing "the reviewer approves"
        (let [out (submit-verdict* reviewer review "approved")]
          (t/is (nil? (:error out)))))

      ;; the marks of a comparison are the branch's, not the review's
      (let [out (th/command! {::th/type :set-branch-change-mark
                              ::rpc/profile-id (:id reviewer)
                              :branch-id (:id branch)
                              :direction :branch->source
                              :change-key (str "shape:" rect-id)
                              :fingerprint "0c0ffee"
                              :reviewed true})]
        (t/is (nil? (:error out))))

      ;; the branch deletes the layer its thread points at: the review is
      ;; open, so the save is the review's activity
      (t/is (nil? (:error (delete-obj* author branch-file-id page-id rect-id))))

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id author)
                              :branch-id (:id branch)})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (run-gc!)

      (t/testing "the branch file is gone"
        (t/is (nil? (row :file branch-file-id))))

      (t/testing "main does not list the threads the merge left behind"
        (t/is (empty? (threads-of author (:id file)))))

      (t/testing "the threads read through their review, with no file"
        (let [out     (th/command! {::th/type :get-branch-review-threads
                                    ::rpc/profile-id (:id author)
                                    :review-id (:id review)})
              threads (:result out)
              by-id   (into {} (map (juxt :id identity)) threads)
              quiet   (get by-id (:id thread))
              closed  (get by-id (:thread-id task))]
          (t/is (nil? (:error out)))
          (t/is (= #{(:id thread) (:thread-id task)} (set (keys by-id))))
          (t/is (every? (comp nil? :file-id) threads))
          (t/is (= ["does this keep the token?"] (mapv :content (:comments quiet))))
          (t/is (= [] (:tasks quiet)))
          (t/is (= :layer (:anchor-type quiet)))
          (t/is (= rect-id (:shape-id quiet)))
          (t/is (= ["Drop the promo"] (mapv :content (:comments closed))))
          (t/is (= [(:id task)] (mapv :id (:tasks closed))))
          (t/is (= ["wont-do"] (mapv :status (:tasks closed))))
          (t/is (nil? (:file-id (row :file-task (:id task)))))))

      (t/testing "a thread left behind moves to main with its task, the branch file gone"
        (let [out   (th/command! {::th/type :move-branch-review-thread
                                  ::rpc/profile-id (:id author)
                                  :thread-id (:thread-id task)})
              tasks (:result (th/command! {::th/type :get-file-tasks
                                           ::rpc/profile-id (:id author)
                                           :file-id (:id file)}))
              event (last (:result (th/command! {::th/type :get-task-events
                                                 ::rpc/profile-id (:id author)
                                                 :id (:id task)})))]
          (t/is (nil? (:error out)))
          (t/is (= [(:thread-id task)] (mapv :id (threads-of author (:id file)))))
          (t/is (= (:id file) (:file-id (row :comment-thread (:thread-id task)))))
          (t/is (= [(:id task)] (mapv :id tasks)))
          (t/is (= (:id file) (:file-id (first tasks))))
          (t/is (some? (:merge-id (first tasks))))
          (t/is (= "merged" (:type event)))
          (t/is (= "review-pay-button" (-> event :payload :branch-name)))))

      (t/testing "the review reads on main with its verdict"
        (let [result (review-of author (:id review))]
          (t/is (some? result))
          (t/is (= "Checkout redesign" (:title result)))
          (t/is (= "merged" (:status result)))
          (t/is (= "approved" (:overall-verdict result)))
          (t/is (= [(:id reviewer)] (mapv :profile-id (:reviews result))))
          (t/is (= ["approved"] (mapv :verdict (:reviews result))))
          (t/is (= (:id file) (:target-file-id result)))))

      (t/testing "the activity of the review is kept"
        (let [{:keys [entries sessions people]} (activity-of author (:id review))]
          (t/is (= 1 people))
          (t/is (= 1 (count sessions)))
          (t/is (= [[rect-id]]
                   (mapv (fn [sitting] (mapv :id (:entries sitting))) sessions)))
          (t/is (= [rect-id] (mapv :id entries)))
          (t/is (= [:deleted] (mapv :kind entries)))))

      (t/testing "the branch edge outlives the file and still names the target"
        (let [edge (row :file-branch (:id branch))]
          (t/is (some? edge))
          (t/is (nil? (:branch-file-id edge)))
          (t/is (= "merged" (:status edge)))
          (t/is (= (:id file) (:source-file-id edge)))))

      (t/testing "the compare marks die with the branch file"
        (t/is (empty? (th/db-query :file-branch-change-mark
                                   {:file-branch-id (:id branch)})))))))

(defn- add-shape*
  [profile file-id page-id {:keys [id parent-id frame-id] :as attrs}]
  (update-file* profile file-id
                [{:type :add-obj
                  :page-id page-id
                  :id id
                  :parent-id parent-id
                  :frame-id frame-id
                  :obj (cts/setup-shape attrs)}]))

(t/deftest a-thread-left-behind-lands-later-on-its-nearest-ancestor
  ;; A review thread with no open task stays on the branch at the merge.
  ;; Its layer sits in a group the merge left out. Once the GC took the
  ;; branch file, moving the thread lands it on the board that held the
  ;; group, the nearest ancestor main has, from what the merge stored on
  ;; the thread, and the thread keeps the name of its lost layer.
  (with-redefs [cf/flags (flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          _        (th/create-team-role* {:team-id (:default-team-id author)
                                          :profile-id (:id reviewer)
                                          :role :editor})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})
          page-id  (page-id-of author (:id file))
          board-id (uuid/random)
          group-id (uuid/random)
          badge-id (uuid/random)
          _        (add-shape* author (:id file) page-id
                               {:id board-id :name "Checkout" :type :frame
                                :x 0 :y 0 :width 400 :height 800
                                :parent-id uuid/zero :frame-id uuid/zero})
          branch   (create-branch* author (:id file) "promo-badge")
          bfile-id (:branch-file-id branch)
          review   (create-review* author branch reviewer)]
      (add-shape* author bfile-id page-id
                  {:id group-id :name "Promo" :type :group
                   :x 20 :y 100 :width 300 :height 48
                   :parent-id board-id :frame-id board-id})
      (add-shape* author bfile-id page-id
                  {:id badge-id :name "Badge" :type :rect
                   :x 20 :y 100 :width 300 :height 48
                   :parent-id group-id :frame-id board-id})
      ;; something the merge carries, besides the group it leaves out
      (update-file* author bfile-id
                    [{:type :mod-obj :page-id page-id :id board-id
                      :operations [{:type :set :attr :name :val "Checkout v2"}]}])
      (let [thread (create-thread* reviewer bfile-id page-id
                                   {:frame-id board-id
                                    :anchor {:type :layer :shape-id badge-id}
                                    :review-id (:id review)})
            ;; a closed task on the same layer: its own thread stays behind
            ;; too, and the task follows that thread when it moves
            task   (:result (th/command! {::th/type :create-task
                                          ::rpc/profile-id (:id reviewer)
                                          :file-id bfile-id
                                          :page-id page-id
                                          :frame-id board-id
                                          :shape-id badge-id
                                          :position (gpt/point 320 100)
                                          :review-id (:id review)
                                          :title "Shorten the badge"}))
            _      (t/is (nil? (:error (th/command! {::th/type :set-task-status
                                                     ::rpc/profile-id (:id reviewer)
                                                     :id (:id task)
                                                     :status "wont-do"}))))
            out    (th/command! {::th/type :merge-file-branch
                                 ::rpc/profile-id (:id author)
                                 :branch-id (:id branch)
                                 :excluded #{(bm/change-key {:kind :shape :page-id page-id :id group-id})}})]
        (t/is (nil? (:error out)))

        (t/testing "the merge leaves the thread behind with what a later move needs"
          (let [stored (row :comment-thread (:id thread))]
            (t/is (= bfile-id (:file-id stored)))
            (t/is (= "Badge" (:lost-shape-name stored)))
            (t/is (= [group-id board-id] (db/decode-pgarray (:landing-ancestors stored) [])))
            (t/is (some? (:landing-area stored)))))

        (run-gc!)
        (t/is (nil? (row :file bfile-id)))

        (t/testing "the review lists the thread with the name of its lost layer only"
          (let [listed (->> (th/command! {::th/type :get-branch-review-threads
                                          ::rpc/profile-id (:id author)
                                          :review-id (:id review)})
                            :result
                            (some #(when (= (:id thread) (:id %)) %)))]
            (t/is (= "Badge" (:lost-shape-name listed)))
            (t/is (not (contains? listed :landing-ancestors)))
            (t/is (not (contains? listed :landing-area)))))

        (t/testing "the later move lands it on the board, the group being absent"
          (let [out   (th/command! {::th/type :move-branch-review-thread
                                    ::rpc/profile-id (:id author)
                                    :thread-id (:id thread)})
                moved (row :comment-thread (:id thread))]
            (t/is (nil? (:error out)))
            (t/is (= (:id file) (:file-id moved)))
            (t/is (= "layer" (:anchor-type moved)))
            (t/is (= board-id (:shape-id moved)))
            (t/is (= board-id (:frame-id moved)))
            (t/is (= "Badge" (:lost-shape-name moved)))
            (t/is (nil? (:landing-ancestors moved)))
            (t/is (nil? (:landing-area moved)))))

        (t/testing "a closed task lands with its thread on the later move"
          (let [out   (th/command! {::th/type :move-branch-review-thread
                                    ::rpc/profile-id (:id author)
                                    :thread-id (:thread-id task)})
                moved (row :file-task (:id task))]
            (t/is (nil? (:error out)))
            (t/is (= (:id file) (:file-id moved)))
            (t/is (= board-id (:shape-id moved)))
            (t/is (= "Badge" (:lost-shape-name moved)))))))))

(t/deftest a-deleted-unmerged-branch-takes-its-thread-and-review-with-it
  ;; The mirror: nothing moved a thread or a task to the target, so the
  ;; branch was all they had. Deleting it deletes them, its review with
  ;; its verdicts and activity, and the edge itself.
  (with-redefs [cf/flags (flags)]
    (let [{:keys [author reviewer page-id file branch branch-file-id review]}
          (setup-reviewed-branch)

          thread (create-thread* author branch-file-id page-id {:anchor {:type :point}})
          task   (:result (th/command! {::th/type :create-task
                                        ::rpc/profile-id (:id author)
                                        :file-id branch-file-id
                                        :page-id page-id
                                        :frame-id uuid/zero
                                        :position (gpt/point 0)
                                        :title "Use the secondary token"}))]

      (t/is (nil? (:error (submit-verdict* reviewer review "approved"))))

      (let [out (th/command! {::th/type :set-branch-change-mark
                              ::rpc/profile-id (:id reviewer)
                              :branch-id (:id branch)
                              :direction :branch->source
                              :change-key "shape:pay-button"
                              :fingerprint "0c0ffee"
                              :reviewed true})]
        (t/is (nil? (:error out))))

      ;; a save of the branch while the review is open records activity
      (t/is (nil? (:error (add-rect* author branch-file-id page-id
                                     (uuid/random) "Badge"))))

      (let [out (th/command! {::th/type :delete-file-branch
                              ::rpc/profile-id (:id author)
                              :branch-id (:id branch)})]
        (t/is (nil? (:error out))))

      (run-gc!)

      (t/testing "the branch file and its edge are gone"
        (t/is (nil? (row :file branch-file-id)))
        (t/is (nil? (row :file-branch (:id branch))))
        (t/is (empty? (th/db-query :file-branch-change-mark
                                   {:file-branch-id (:id branch)}))))

      (t/testing "the thread is gone"
        (t/is (nil? (row :comment-thread (:id thread))))
        (t/is (= [] (threads-of author (:id file)))))

      (t/testing "the task of the branch is gone with its thread"
        (t/is (some? (:id task)))
        (t/is (nil? (row :file-task (:id task))))
        (t/is (nil? (row :comment-thread (:thread-id task)))))

      (t/testing "the review is gone with its verdicts and activity"
        (t/is (nil? (row :file-branch-review (:id review))))
        (t/is (empty? (th/db-query :file-branch-reviewer
                                   {:review-id (:id review)}
                                   {::db/remove-deleted false})))
        (t/is (empty? (th/db-query :file-branch-review-activity
                                   {:review-id (:id review)})))))))

(defn- stacked-branches
  "A file, a branch of it, and a branch of that branch, which only the
  `:branch-stacking` flag allows."
  [profile file]
  (let [parent (create-branch* profile (:id file) "parent")
        child  (create-branch* profile (:branch-file-id parent) "child")]
    {:parent parent :child child}))

(t/deftest deleting-a-file-reaches-the-branches-of-its-branches
  ;; The cascade follows the chain of edges to its end: a grandchild
  ;; branch file is deleted with the file the chain starts from.
  (with-redefs [cf/flags (stack-flags)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
          {:keys [parent child]} (stacked-branches profile file)]

      (t/is (nil? (:error (th/command! {::th/type :delete-file
                                        ::rpc/profile-id (:id profile)
                                        :id (:id file)}))))

      (th/run-pending-jobs)

      (t/testing "the cascade marks both branch files"
        (t/is (some? (:deleted-at (row :file (:branch-file-id parent)))))
        (t/is (some? (:deleted-at (row :file (:branch-file-id child))))))

      (t/is (pos? (:processed (th/run-task! :objects-gc {:skip-delay true}))))

      (t/testing "the file and both branch files are collected"
        (t/is (nil? (row :file (:id file))))
        (t/is (nil? (row :file (:branch-file-id parent))))
        (t/is (nil? (row :file (:branch-file-id child)))))

      (t/testing "their edges go with them, nothing merged them"
        (t/is (nil? (row :file-branch (:id parent))))
        (t/is (nil? (row :file-branch (:id child))))))))

(t/deftest deleting-a-project-reaches-a-grandchild-branch
  ;; The same chain under a project deletion, which reaches the branch
  ;; files both through the project's file list and through the cascade
  ;; of their source, and the family goes once its last file is gone.
  (with-redefs [cf/flags (stack-flags)]
    (let [profile (th/create-profile* 1 {:is-active true})
          project (th/create-project* 81 {:profile-id (:id profile)
                                          :team-id (:default-team-id profile)})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:id project)})
          {:keys [parent child]} (stacked-branches profile file)]

      (t/is (nil? (:error (th/command! {::th/type :delete-project
                                        ::rpc/profile-id (:id profile)
                                        :id (:id project)}))))

      (run-gc!)

      (t/testing "every file of the family is gone"
        (t/is (nil? (row :file (:id file))))
        (t/is (nil? (row :file (:branch-file-id parent))))
        (t/is (nil? (row :file (:branch-file-id child)))))

      (t/testing "the edges are gone and the family with them"
        (t/is (nil? (row :file-branch (:id parent))))
        (t/is (nil? (row :file-branch (:id child))))
        (t/is (nil? (th/db-get :file-family {:id (:id file)})))))))
