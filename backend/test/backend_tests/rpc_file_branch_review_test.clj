;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-branch-review-test
  (:require
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- pr-flags
  "The test flags plus `:branching`. Read at run time, inside the
  `th/state-init` fixture, so the flags it turns off (the admin
  console among them) stay off: a value captured when the namespace
  loads would carry the production defaults instead."
  []
  (conj cf/flags :branching))

(defn- apply-change*
  [profile file-id change]
  (let [f (th/db-get :file {:id file-id})]
    (th/command! {::th/type :update-file
                  ::rpc/profile-id (:id profile)
                  :id file-id
                  :session-id (uuid/random)
                  :revn (:revn f)
                  :vern (:vern f)
                  :features cfeat/supported-features
                  :changes [change]})))

(defn- add-color*
  [profile file-id name]
  (apply-change* profile file-id
                 {:type :add-color
                  :color {:id (uuid/random) :name name :color "#ff0000" :opacity 1}}))

(defn- create-branch*
  [profile file name]
  (:result (th/command! {::th/type :create-file-branch
                         ::rpc/profile-id (:id profile)
                         :file-id (:id file)
                         :name name})))

(defn- create-review*
  [profile branch-id params]
  (th/command! (merge {::th/type :create-branch-review
                       ::rpc/profile-id (:id profile)
                       :branch-id branch-id
                       :title "review me"}
                      params)))

(t/deftest create-and-list-branch-reviews
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          proj-id  (:default-project-id author)
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id proj-id})]

      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id reviewer)
                             :role :editor})

      (let [branch         (create-branch* author file "feature")
            branch-file-id (:branch-file-id branch)
            review-id      (volatile! nil)]

        (add-color* author branch-file-id "Brand")

        (t/testing "create branch review with a reviewer"
          (let [out (create-review* author (:id branch)
                                    {:description "please check the brand color"
                                     :reviewers [(:id reviewer)]})]
            (t/is (nil? (:error out)))
            (let [result (:result out)]
              (t/is (uuid? (:id result)))
              (t/is (= branch-file-id (:source-file-id result)))
              (t/is (= (:id file) (:target-file-id result)))
              (t/is (= "open" (:status result)))
              (t/is (= [(:id reviewer)] (:reviewers result)))
              (vreset! review-id (:id result)))))

        (t/testing "list from the target file"
          (let [out (th/command! {::th/type :get-file-branch-reviews
                                  ::rpc/profile-id (:id author)
                                  :file-id (:id file)})]
            (t/is (nil? (:error out)))
            (let [[row :as result] (:result out)]
              (t/is (= 1 (count result)))
              (t/is (= @review-id (:id row)))
              (t/is (= "review me" (:title row)))
              (t/is (= "feature" (:branch-name row)))
              (t/is (= (:name file) (:target-name row)))
              (t/is (= "pending" (:overall-verdict row)))
              ;; the branch carries one change (the added color)
              (t/is (= 1 (:ahead row)))
              (t/is (= 0 (:behind row)))
              (t/is (= 0 (:conflicts row)))
              (t/is (= 1 (count (:reviews row))))
              (t/is (= (:id reviewer) (-> row :reviews first :profile-id)))
              (t/is (nil? (-> row :reviews first :verdict))))))

        (t/testing "list resolves a branch file id to its target"
          (let [out (th/command! {::th/type :get-file-branch-reviews
                                  ::rpc/profile-id (:id author)
                                  :file-id branch-file-id})]
            (t/is (nil? (:error out)))
            (t/is (= 1 (count (:result out))))))

        (t/testing "get single branch review"
          (let [out (th/command! {::th/type :get-branch-review
                                  ::rpc/profile-id (:id reviewer)
                                  :id @review-id})]
            (t/is (nil? (:error out)))
            (t/is (= "review me" (-> out :result :title)))))))))

(t/deftest one-open-review-per-branch
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "feature")]

      (t/is (nil? (:error (create-review* author (:id branch) {}))))

      (let [out   (create-review* author (:id branch) {:title "second"})
            error (:error out)]
        (t/is (some? error))
        (t/is (= :branch-review-already-exists (-> error ex-data :code)))))))

(t/deftest create-validates-reviewers-and-permissions
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          stranger (th/create-profile* 2 {:is-active true})
          viewer   (th/create-profile* 3 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})
          branch   (create-branch* author file "feature")]

      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id viewer)
                             :role :viewer})

      (t/testing "a reviewer outside the team is refused"
        (let [out   (create-review* author (:id branch)
                                    {:reviewers [(:id stranger)]})
              error (:error out)]
          (t/is (some? error))
          (t/is (= :reviewer-not-team-member (-> error ex-data :code)))))

      (t/testing "the author is silently dropped from the reviewer list"
        (let [out (create-review* author (:id branch)
                                  {:reviewers [(:id author) (:id viewer)]})]
          (t/is (nil? (:error out)))
          (t/is (= [(:id viewer)] (-> out :result :reviewers)))))

      (t/testing "a viewer of the branch file cannot create a branch review"
        (let [branch2 (create-branch* author file "feature-2")
              out     (create-review* viewer (:id branch2) {})]
          (t/is (some? (:error out))))))))

(t/deftest viewer-bundle-plays-the-live-branch
  (with-redefs [cf/flags (pr-flags)]
    (let [author  (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id author)
                                      :project-id (:default-project-id author)})
          branch  (create-branch* author file "feature")
          bfid    (:branch-file-id branch)]

      (add-color* author bfid "One")

      (let [pr (:result (create-review* author (:id branch) {}))]

        ;; the branch keeps moving AFTER the branch review was created
        (add-color* author bfid "Two")

        ;; the bundle strips file :data down to pages/components, so the
        ;; served state is asserted through :revn
        (t/testing "the viewer bundle of a review shows the live branch, read-only"
          (let [out    (th/command! {::th/type :get-view-only-bundle
                                     ::rpc/profile-id (:id author)
                                     :file-id bfid
                                     :review-id (:id pr)})
                result (:result out)
                bf     (th/db-get :file {:id bfid})]
            (t/is (nil? (:error out)))
            (t/is (= (:revn bf) (-> result :file :revn)))
            (t/is (false? (-> result :permissions :can-edit)))))

        (t/testing "the viewer bundle refuses a closed branch review"
          (th/command! {::th/type :close-branch-review
                        ::rpc/profile-id (:id author)
                        :id (:id pr)})
          (let [out   (th/command! {::th/type :get-view-only-bundle
                                    ::rpc/profile-id (:id author)
                                    :file-id bfid
                                    :review-id (:id pr)})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :branch-review-not-open (-> error ex-data :code)))))))))

(t/deftest review-lifecycle
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          other    (th/create-profile* 3 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})]

      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id reviewer)
                             :role :editor})
      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id other)
                             :role :editor})

      (let [branch (create-branch* author file "feature")
            bfid   (:branch-file-id branch)
            _      (add-color* author bfid "Brand")
            pr     (:result (create-review* author (:id branch)
                                            {:reviewers [(:id reviewer)]}))]

        (t/testing "a non-assigned member cannot submit a review"
          (let [out   (th/command! {::th/type :submit-branch-review-verdict
                                    ::rpc/profile-id (:id other)
                                    :id (:id pr)
                                    :verdict "approved"})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :not-a-reviewer (-> error ex-data :code)))))

        (t/testing "an approval flips the aggregate review state"
          (let [out (th/command! {::th/type :submit-branch-review-verdict
                                  ::rpc/profile-id (:id reviewer)
                                  :id (:id pr)
                                  :verdict "approved"
                                  :comment "ship it"})]
            (t/is (nil? (:error out))))
          (let [out (th/command! {::th/type :get-branch-review
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})
                row (:result out)]
            (t/is (= "approved" (:overall-verdict row)))
            (t/is (false? (-> row :reviews first :stale)))))

        (t/testing "a save of the branch makes the verdict stale"
          (add-color* author bfid "Another")
          (let [row (:result (th/command! {::th/type :get-branch-review
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            ;; the verdict is kept for the history but no longer current
            (t/is (= "in-review" (:overall-verdict row)))
            (t/is (true? (-> row :reviews first :stale)))))

        (t/testing "requesting changes beats an approval"
          (let [out (th/command! {::th/type :submit-branch-review-verdict
                                  ::rpc/profile-id (:id reviewer)
                                  :id (:id pr)
                                  :verdict "changes-requested"
                                  :comment "the second color is off-brand"})]
            (t/is (nil? (:error out))))
          (let [row (:result (th/command! {::th/type :get-branch-review
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            (t/is (= "changes-requested" (:overall-verdict row)))))))))

(t/deftest verdict-goes-stale-after-a-branch-save
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          team-id  (:default-team-id author)
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})]

      (th/create-team-role* {:team-id team-id
                             :profile-id (:id reviewer)
                             :role :editor})

      (let [branch   (create-branch* author file "feature")
            bfid     (:branch-file-id branch)
            _        (add-color* author bfid "Brand")
            pr       (:result (create-review* author (:id branch)
                                              {:reviewers [(:id reviewer)]}))
            review   (fn []
                       (:result (th/command! {::th/type :get-branch-review
                                              ::rpc/profile-id (:id author)
                                              :id (:id pr)})))
            listed   (fn []
                       (->> (th/command! {::th/type :get-file-branch-reviews
                                          ::rpc/profile-id (:id author)
                                          :file-id (:id file)})
                            :result
                            (filter #(= (:id pr) (:id %)))
                            (first)))
            requests (fn []
                       (->> (th/command! {::th/type :get-profile-review-requests
                                          ::rpc/profile-id (:id reviewer)
                                          :team-id team-id})
                            :result
                            (mapv :id)))]

        (t/testing "a pending reviewer is asked for a verdict"
          (t/is (= [(:id pr)] (requests))))

        (t/testing "a verdict records the live revn of the branch file"
          (let [out (th/command! {::th/type :submit-branch-review-verdict
                                  ::rpc/profile-id (:id reviewer)
                                  :id (:id pr)
                                  :verdict "approved"})]
            (t/is (nil? (:error out)))
            (t/is (= (:revn (th/db-get :file {:id bfid}))
                     (-> out :result :verdict-revn)))))

        (t/testing "the verdict is current before any save of the branch"
          (let [row (review)]
            (t/is (= "approved" (:overall-verdict row)))
            (t/is (false? (-> row :reviews first :stale)))
            (t/is (not (contains? row :outdated)))
            (t/is (not (contains? row :pinned-revn))))
          (t/is (false? (-> (listed) :reviews first :stale)))
          (t/is (= [] (requests))))

        (t/testing "a save of the branch file makes the verdict stale"
          (t/is (nil? (:error (add-color* author bfid "Accent"))))
          (let [row (review)]
            (t/is (= "in-review" (:overall-verdict row)))
            (t/is (= "approved" (-> row :reviews first :verdict)))
            (t/is (true? (-> row :reviews first :stale))))
          (t/is (true? (-> (listed) :reviews first :stale)))
          (t/is (= [(:id pr)] (requests))))

        (t/testing "a new verdict over the live branch is current again"
          (th/command! {::th/type :submit-branch-review-verdict
                        ::rpc/profile-id (:id reviewer)
                        :id (:id pr)
                        :verdict "approved"})
          (let [row (review)]
            (t/is (= "approved" (:overall-verdict row)))
            (t/is (false? (-> row :reviews first :stale))))
          (t/is (= [] (requests))))))))

(t/deftest update-branch-review-metadata-and-reviewers
  (with-redefs [cf/flags (pr-flags)]
    (let [author     (th/create-profile* 1 {:is-active true})
          reviewer-1 (th/create-profile* 2 {:is-active true})
          reviewer-2 (th/create-profile* 3 {:is-active true})
          editor     (th/create-profile* 4 {:is-active true})
          file       (th/create-file* 1 {:profile-id (:id author)
                                         :project-id (:default-project-id author)})]

      (doseq [[profile role] [[reviewer-1 :editor] [reviewer-2 :editor] [editor :editor]]]
        (th/create-team-role* {:team-id (:default-team-id author)
                               :profile-id (:id profile)
                               :role role}))

      (let [branch (create-branch* author file "feature")
            pr     (:result (create-review* author (:id branch)
                                            {:reviewers [(:id reviewer-1)]}))]

        (t/testing "a plain editor (not author, not admin) cannot manage it"
          (let [out (th/command! {::th/type :update-branch-review-info
                                  ::rpc/profile-id (:id editor)
                                  :id (:id pr)
                                  :title "hijacked"})]
            (t/is (some? (:error out)))))

        (t/testing "the author can replace title, description and reviewers"
          (let [out (th/command! {::th/type :update-branch-review-info
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)
                                  :title "better title"
                                  :description "more context"
                                  :reviewers [(:id reviewer-2)]})]
            (t/is (nil? (:error out))))
          (let [row (:result (th/command! {::th/type :get-branch-review
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            (t/is (= "better title" (:title row)))
            (t/is (= "more context" (:description row)))
            (t/is (= [(:id reviewer-2)] (mapv :profile-id (:reviews row))))))

        (t/testing "a removed reviewer can no longer submit"
          (let [out   (th/command! {::th/type :submit-branch-review-verdict
                                    ::rpc/profile-id (:id reviewer-1)
                                    :id (:id pr)
                                    :verdict "approved"})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :not-a-reviewer (-> error ex-data :code)))))))))

(t/deftest close-and-reopen-branch-review
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          editor (th/create-profile* 2 {:is-active true})
          admin  (th/create-profile* 3 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})]

      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id editor)
                             :role :editor})
      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id admin)
                             :role :admin})

      (let [branch (create-branch* author file "feature")
            pr     (:result (create-review* author (:id branch) {}))]

        (t/testing "a plain editor cannot close someone else's branch review"
          (let [out (th/command! {::th/type :close-branch-review
                                  ::rpc/profile-id (:id editor)
                                  :id (:id pr)})]
            (t/is (some? (:error out)))))

        (t/testing "a team admin can close it"
          (let [out (th/command! {::th/type :close-branch-review
                                  ::rpc/profile-id (:id admin)
                                  :id (:id pr)})]
            (t/is (nil? (:error out)))
            (t/is (= "closed" (-> out :result :status)))))

        (t/testing "the author can reopen it"
          (let [out (th/command! {::th/type :reopen-branch-review
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (nil? (:error out)))
            (t/is (= "open" (-> out :result :status)))))))))

(t/deftest branch-lifecycle-closes-reviews
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})]

      (t/testing "merging the branch marks the branch review merged"
        (let [branch (create-branch* author file "merge-me")
              _      (add-color* author (:branch-file-id branch) "Brand")
              pr     (:result (create-review* author (:id branch) {}))
              out    (th/command! {::th/type :merge-file-branch
                                   ::rpc/profile-id (:id author)
                                   :branch-id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-branch-review {:id (:id pr)})]
            (t/is (= "merged" (:status row)))
            (t/is (some? (:closed-at row)))
            ;; the row survives as history even though the branch is gone
            (t/is (nil? (:deleted-at row))))
          ;; the record of the merge names the review it accepted, so the
          ;; list of merges keeps it once the review is gone
          (let [merges (:result (th/command! {::th/type :get-file-branch-merges
                                              ::rpc/profile-id (:id author)
                                              :file-id (:id file)}))]
            (t/is (= [(:id pr)] (mapv :review-id merges)))
            (t/is (= [(:title pr)] (mapv :review-title merges))))))

      (t/testing "deleting the branch closes the branch review"
        (let [branch (create-branch* author file "delete-me")
              pr     (:result (create-review* author (:id branch) {}))
              out    (th/command! {::th/type :delete-file-branch
                                   ::rpc/profile-id (:id author)
                                   :branch-id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-branch-review {:id (:id pr)})]
            (t/is (= "closed" (:status row))))))

      (t/testing "archiving the branch closes the branch review"
        (let [branch (create-branch* author file "archive-me")
              pr     (:result (create-review* author (:id branch) {}))
              out    (th/command! {::th/type :archive-file-branch
                                   ::rpc/profile-id (:id author)
                                   :branch-id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-branch-review {:id (:id pr)})]
            (t/is (= "closed" (:status row)))))))))

(t/deftest applying-the-branch-completes-the-branch-review
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "apply-me")
          _      (add-color* author (:branch-file-id branch) "Brand")
          pr     (:result (create-review* author (:id branch) {}))
          out    (th/command! {::th/type :apply-file-branch
                               ::rpc/profile-id (:id author)
                               :branch-id (:id branch)})]
      (t/is (nil? (:error out)))
      (t/is (= :applied (-> out :result :status)))

      (t/testing "the review ends as merged"
        (let [[row] (th/db-query :file-branch-review {:id (:id pr)})]
          (t/is (= "merged" (:status row)))
          (t/is (some? (:closed-at row)))))

      (t/testing "the record of the apply names the review"
        (let [merges (:result (th/command! {::th/type :get-file-branch-merges
                                            ::rpc/profile-id (:id author)
                                            :file-id (:id file)}))]
          (t/is (= [(:id pr)] (mapv :review-id merges)))
          (t/is (= ["apply"] (mapv :kind merges)))))

      (t/testing "the branch stays open for a new review"
        (add-color* author (:branch-file-id branch) "Accent")
        (let [out (create-review* author (:id branch) {:title "second round"})]
          (t/is (nil? (:error out)))
          (t/is (= "open" (-> out :result :status))))))))

(t/deftest create-serializes-against-a-concurrent-merge
  ;; the create must re-read the branch under the advisory lock: a merge
  ;; that wins the race closes the branch while the create waits for the
  ;; lock, so a create arriving afterwards must refuse instead of opening
  ;; a review over a branch that is already merged and gone.
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "contested")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)]
      (add-color* author branch-file-id "Brand")
      (let [orig-lock db/xact-lock!
            merging?  (atom false)
            merged    (atom nil)]
        (with-redefs [db/xact-lock!
                      (fn [conn id]
                        ;; the first lock the create takes is the signal:
                        ;; merge the same branch through the pool in its
                        ;; own transaction BEFORE the create locks
                        (when (compare-and-set! merging? false true)
                          (reset! merged (th/command! {::th/type :merge-file-branch
                                                       ::rpc/profile-id (:id author)
                                                       :branch-id branch-id})))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :create-branch-review
                                  ::rpc/profile-id (:id author)
                                  :branch-id branch-id
                                  :title "review me"})]
            (t/is (= :merged (-> @merged :result :status)))
            (t/is (= :branch-not-found (-> out :error ex-data :code)))
            (t/is (= [] (th/db-query :file-branch-review {:file-branch-id branch-id})))))))))

(t/deftest reopen-serializes-against-a-concurrent-merge
  ;; the reopen must re-read the branch under the advisory lock too: the
  ;; merge that wins the race closes the branch under the closed review's
  ;; feet, so a reopen arriving afterwards must refuse instead of
  ;; reviving a review over a branch that is already merged and gone.
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "contested")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)]
      (add-color* author branch-file-id "Brand")
      (let [pr (:result (create-review* author branch-id {}))]
        (t/is (nil? (:error (th/command! {::th/type :close-branch-review
                                          ::rpc/profile-id (:id author)
                                          :id (:id pr)}))))
        (let [orig-lock db/xact-lock!
              merging?  (atom false)
              merged    (atom nil)]
          (with-redefs [db/xact-lock!
                        (fn [conn id]
                          ;; the first lock the reopen takes is the signal:
                          ;; merge the same branch through the pool in its
                          ;; own transaction BEFORE the reopen locks
                          (when (compare-and-set! merging? false true)
                            (reset! merged (th/command! {::th/type :merge-file-branch
                                                         ::rpc/profile-id (:id author)
                                                         :branch-id branch-id})))
                          (orig-lock conn id))]
            (let [out (th/command! {::th/type :reopen-branch-review
                                    ::rpc/profile-id (:id author)
                                    :id (:id pr)})]
              (t/is (= :merged (-> @merged :result :status)))
              (t/is (= :branch-not-open (-> out :error ex-data :code)))
              (t/is (= "closed" (:status (first (th/db-query :file-branch-review {:id (:id pr)}))))))))))))

(t/deftest closed-branch-reviews-listing
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "feature")
          pr     (:result (create-review* author (:id branch) {}))]

      (th/command! {::th/type :close-branch-review
                    ::rpc/profile-id (:id author)
                    :id (:id pr)})

      (t/testing "closed branch reviews are hidden by default"
        (let [out (th/command! {::th/type :get-file-branch-reviews
                                ::rpc/profile-id (:id author)
                                :file-id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (= 0 (count (:result out))))))

      (t/testing "and visible with include-closed"
        (let [out (th/command! {::th/type :get-file-branch-reviews
                                ::rpc/profile-id (:id author)
                                :file-id (:id file)
                                :include-closed true})]
          (t/is (nil? (:error out)))
          (t/is (= 1 (count (:result out))))
          (t/is (= "closed" (-> out :result first :status))))))))

(t/deftest review-listing-shares-the-branch-summary-cache
  (with-redefs [cf/flags (pr-flags)]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "feature")
          branches (fn [] (:result (th/command! {::th/type :get-file-branches
                                                 ::rpc/profile-id (:id author)
                                                 :file-id (:id file)})))
          prs      (fn [] (:result (th/command! {::th/type :get-file-branch-reviews
                                                 ::rpc/profile-id (:id author)
                                                 :file-id (:id file)})))
          summary  (fn [row] (select-keys row [:ahead :behind :conflicts]))]

      ;; diverge both sides so the comparison is real work
      (add-color* author (:branch-file-id branch) "Brand")
      (add-color* author (:id file) "Accent")
      (t/is (nil? (:error (create-review* author (:id branch) {}))))

      (let [orig-merge bm/compute-merge
            calls      (atom 0)]
        (with-redefs [bm/compute-merge (fn [& args] (swap! calls inc) (apply orig-merge args))]
          (let [cold-branch (first (branches))
                cold-calls  @calls
                _           (reset! calls 0)
                warm-pr     (first (prs))
                pr-calls    @calls
                _           (reset! calls 0)
                repeat-pr   (first (prs))]

            (t/testing "the branches listing pays for the comparison"
              (t/is (pos? cold-calls))
              (t/is (= {:ahead 1 :behind 1 :conflicts 0} (summary cold-branch))))

            (t/testing "the review listing arrives warm and agrees"
              (t/is (zero? pr-calls))
              (t/is (= (summary cold-branch) (summary warm-pr))))

            (t/testing "and a repeat of it performs no comparison work either"
              (t/is (zero? @calls))
              (t/is (= (summary warm-pr) (summary repeat-pr))))))))))

(t/deftest branching-flag-gates-review-commands
  (let [author (th/create-profile* 1 {:is-active true})
        file   (th/create-file* 1 {:profile-id (:id author)
                                   :project-id (:default-project-id author)})
        branch (with-redefs [cf/flags (pr-flags)]
                 (create-branch* author file "feature"))
        out    (create-review* author (:id branch) {})
        error  (:error out)]
    (t/is (some? error))
    (t/is (= :branching-disabled (-> error ex-data :code)))))

(t/deftest review-commands-notify-only-after-the-commit
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})]
      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id reviewer)
                             :role :editor})
      (let [branch (create-branch* author file "notify")
            bfid   (:branch-file-id branch)
            actors {:author (:id author) :reviewer (:id reviewer)}
            ;; one case per mutation; at the very moment `mbus/pub!`
            ;; fires, `:view` reads the row ANOTHER pool connection sees
            ;; and `:expected` is the state that must already be visible
            ;; there (the transaction has committed)
            cases
            [{:label "create-branch-review"
              :type :create-branch-review
              :actor :author
              :params (fn [_] {:branch-id (:id branch)
                               :title "review me"
                               :reviewers [(:id reviewer)]})
              :message-type :branch-review-created
              :view (fn [id] (select-keys (th/db-get :file-branch-review {:id id})
                                          [:status]))
              :expected (constantly {:status "open"})}

             {:label "update-branch-review-info"
              :type :update-branch-review-info
              :actor :author
              :params (fn [id] {:id id :title "renamed"})
              :message-type :branch-review-updated
              :view (fn [id] (select-keys (th/db-get :file-branch-review {:id id})
                                          [:title]))
              :expected (constantly {:title "renamed"})}

             {:label "submit-branch-review-verdict"
              :type :submit-branch-review-verdict
              :actor :reviewer
              :params (fn [id] {:id id :verdict "approved" :comment "ship it"})
              :message-type :branch-review-verdict-submitted
              :view (fn [id] (select-keys (th/db-get :file-branch-reviewer
                                                     {:review-id id
                                                      :profile-id (:id reviewer)})
                                          [:verdict :verdict-revn]))
              :expected (fn [_] {:verdict "approved"
                                 :verdict-revn (:revn (th/db-get :file {:id bfid}))})}

             {:label "close-branch-review"
              :type :close-branch-review
              :actor :author
              :params (fn [id] {:id id})
              :message-type :branch-review-closed
              :view (fn [id] (select-keys (th/db-get :file-branch-review {:id id})
                                          [:status]))
              :expected (constantly {:status "closed"})}

             {:label "reopen-branch-review"
              :type :reopen-branch-review
              :actor :author
              :params (fn [id] {:id id})
              :message-type :branch-review-updated
              :view (fn [id] (select-keys (th/db-get :file-branch-review {:id id})
                                          [:status]))
              :expected (constantly {:status "open"})}]]
        (loop [cases cases
               review-id nil]
          (when-some [{:keys [label type actor params message-type view expected]}
                      (first cases)]
            (let [seen (atom [])
                  out  (with-redefs [mbus/pub!
                                     (fn [_ & {:keys [message]}]
                                       (swap! seen conj {:message message
                                                         :row (view (:review-id message))}))]
                         (th/command! (merge {::th/type type
                                              ::rpc/profile-id (actors actor)}
                                             (params review-id))))]
              (t/is (nil? (:error out)) (str label " runs"))
              (let [{:keys [message row]} (first @seen)]
                (t/is (= 1 (count @seen)) (str label " publishes once"))
                (t/is (= message-type (:type message)) (str label " message type"))
                (t/is (= (expected (:review-id message)) row)
                      (str label " publishes only after the commit")))
              (recur (rest cases) (or review-id (-> out :result :id))))))))))

(t/deftest rolled-back-review-commands-notify-nothing
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          reviewer (th/create-profile* 2 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})]
      (th/create-team-role* {:team-id (:default-team-id author)
                             :profile-id (:id reviewer)
                             :role :editor})
      (let [branch (create-branch* author file "rollback")
            seen   (atom [])]
        ;; the reviewer emails are queued inside the transaction: make
        ;; that queueing fail after the branch review row is written
        (with-redefs [mbus/pub!         (fn [_ & {:keys [message]}]
                                          (swap! seen conj message))
                      eml/send-template (fn [_ _] (throw (IllegalStateException. "boom")))]
          (let [out (create-review* author (:id branch) {:reviewers [(:id reviewer)]})]
            (t/is (some? (:error out)))))
        (t/is (empty? @seen))
        (t/is (empty? (th/db-query :file-branch-review
                                   {:file-branch-id (:id branch)})))))))

(t/deftest activity-folds-every-row-not-the-newest-window
  (with-redefs [cf/flags (pr-flags)]
    (let [author   (th/create-profile* 1 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id author)
                                       :project-id (:default-project-id author)})
          page-id  (-> (th/command! {::th/type :get-file
                                     ::rpc/profile-id (:id author)
                                     :id (:id file)})
                       :result :data :pages first)
          branch    (create-branch* author file "feature")
          review-id (-> (create-review* author (:id branch) {}) :result :id)
          paid-id   (uuid/random)
          board-id  (uuid/random)
          promo-id  (uuid/random)
          start     (ct/now)

          paid     {:id paid-id :page-id page-id :name "Pay" :type :rect
                    :frame-id board-id :frame-name "Checkout" :kind :added}
          promo    {:id promo-id :page-id page-id :name "Promo" :type :rect
                    :frame-id board-id :frame-name "Checkout" :kind :added}
          moved    {:id board-id :page-id page-id :name "Checkout" :type :frame
                    :frame-id uuid/zero :frame-name nil :kind :modified
                    :attrs {:x [0 10]}}
          changed  (assoc paid :name "Pay now" :kind :modified
                          :attrs {:name ["Pay" "Pay now"]})

          row
          (fn [revn at entries]
            [(uuid/next) review-id (:id author) revn
             (blob/encode entries)
             at])

          ;; 2001 saves: the oldest one adds the layers and the newest
          ;; one renames one of them and deletes the other. The saves in
          ;; between keep the first one outside a 2000-row window, and a
          ;; gap longer than a sitting opens a second sitting, so the
          ;; fold crosses one.
          rows
          (into [(row 0 start [paid promo])]
                (concat (map (fn [i]
                               (row i
                                    (ct/plus start (ct/duration {:hours 2 :seconds i}))
                                    [moved]))
                             (range 1 2000))
                        [(row 2000
                              (ct/plus start (ct/duration {:hours 2 :seconds 2000}))
                              [changed (assoc promo :kind :deleted)])]))]

      (db/insert-many! th/*pool* :file-branch-review-activity
                       [:id :review-id :profile-id :revn :entries :created-at]
                       rows
                       {::db/return-keys false})

      (let [out    (th/command! {::th/type :get-branch-review-activity
                                 ::rpc/profile-id (:id author)
                                 :id review-id})
            result (:result out)
            entry  (->> (:entries result)
                        (some #(when (= paid-id (:id %)) %)))]
        (t/is (nil? (:error out)))
        (t/is (= :added (:kind entry))
              "the oldest save still counts in what the layer ends up as")
        (t/is (= "Pay now" (:name entry))
              "the layer keeps the name of its last change")
        (t/is (not-any? #(= promo-id (:id %)) (:entries result))
              "a layer added and then deleted is no change")
        (t/is (= 2 (count (:entries result))))
        (t/is (= 2 (count (:sessions result))))
        (t/is (= 1 (:people result)))
        (t/is (= (:id author) (-> result :last-save :profile-id)))))))
