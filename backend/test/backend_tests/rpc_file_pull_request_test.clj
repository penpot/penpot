;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-pull-request-test
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private pr-flags
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

(defn- create-pr*
  [profile branch-id params]
  (th/command! (merge {::th/type :create-pull-request
                       ::rpc/profile-id (:id profile)
                       :branch-id branch-id
                       :title "review me"}
                      params)))

(t/deftest create-and-list-pull-requests
  (with-redefs [cf/flags pr-flags]
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
            pr-id          (volatile! nil)]

        (add-color* author branch-file-id "Brand")

        (t/testing "create pull request with a reviewer"
          (let [out (create-pr* author (:id branch)
                                {:description "please check the brand color"
                                 :reviewers [(:id reviewer)]})]
            (t/is (nil? (:error out)))
            (let [result (:result out)]
              (t/is (uuid? (:id result)))
              (t/is (= branch-file-id (:source-file-id result)))
              (t/is (= (:id file) (:target-file-id result)))
              (t/is (= "open" (:status result)))
              (t/is (= [(:id reviewer)] (:reviewers result)))
              (vreset! pr-id (:id result)))))

        (t/testing "review snapshot is pinned on the branch file"
          (let [rows (->> (th/db-query :file-change {:file-id branch-file-id})
                          (filter #(some-> (:label %) (str/starts-with? "pr-review/"))))]
            (t/is (= 1 (count rows)))
            (t/is (every? (fn [{:keys [deleted-at]}]
                            (and (some? deleted-at)
                                 (ct/is-after? deleted-at (ct/in-future {:days 3000}))))
                          rows))))

        (t/testing "list from the target file"
          (let [out (th/command! {::th/type :get-file-pull-requests
                                  ::rpc/profile-id (:id author)
                                  :file-id (:id file)})]
            (t/is (nil? (:error out)))
            (let [[row :as result] (:result out)]
              (t/is (= 1 (count result)))
              (t/is (= @pr-id (:id row)))
              (t/is (= "review me" (:title row)))
              (t/is (= "feature" (:branch-name row)))
              (t/is (= (:name file) (:target-name row)))
              (t/is (= "pending" (:review-state row)))
              (t/is (false? (:outdated row)))
              ;; the branch carries one change (the added color)
              (t/is (= 1 (:ahead row)))
              (t/is (= 0 (:behind row)))
              (t/is (= 0 (:conflicts row)))
              (t/is (= 1 (count (:reviews row))))
              (t/is (= (:id reviewer) (-> row :reviews first :profile-id)))
              (t/is (= "pending" (-> row :reviews first :state))))))

        (t/testing "list resolves a branch file id to its target"
          (let [out (th/command! {::th/type :get-file-pull-requests
                                  ::rpc/profile-id (:id author)
                                  :file-id branch-file-id})]
            (t/is (nil? (:error out)))
            (t/is (= 1 (count (:result out))))))

        (t/testing "get single pull request"
          (let [out (th/command! {::th/type :get-pull-request
                                  ::rpc/profile-id (:id reviewer)
                                  :id @pr-id})]
            (t/is (nil? (:error out)))
            (t/is (= "review me" (-> out :result :title)))))))))

(t/deftest one-open-pull-request-per-branch
  (with-redefs [cf/flags pr-flags]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "feature")]

      (t/is (nil? (:error (create-pr* author (:id branch) {}))))

      (let [out   (create-pr* author (:id branch) {:title "second"})
            error (:error out)]
        (t/is (some? error))
        (t/is (= :pull-request-already-exists (-> error ex-data :code)))))))

(t/deftest create-validates-reviewers-and-permissions
  (with-redefs [cf/flags pr-flags]
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
        (let [out   (create-pr* author (:id branch)
                                {:reviewers [(:id stranger)]})
              error (:error out)]
          (t/is (some? error))
          (t/is (= :reviewer-not-team-member (-> error ex-data :code)))))

      (t/testing "the author is silently dropped from the reviewer list"
        (let [out (create-pr* author (:id branch)
                              {:reviewers [(:id author) (:id viewer)]})]
          (t/is (nil? (:error out)))
          (t/is (= [(:id viewer)] (-> out :result :reviewers)))))

      (t/testing "a viewer of the branch file cannot create a pull request"
        (let [branch2 (create-branch* author file "feature-2")
              out     (create-pr* viewer (:id branch2) {})]
          (t/is (some? (:error out))))))))

(t/deftest bundle-serves-the-pinned-snapshot
  (with-redefs [cf/flags pr-flags]
    (let [author  (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id author)
                                      :project-id (:default-project-id author)})
          branch  (create-branch* author file "feature")
          bfid    (:branch-file-id branch)
          color-1 (uuid/random)
          color-2 (uuid/random)]

      (apply-change* author bfid
                     {:type :add-color
                      :color {:id color-1 :name "One" :color "#111111" :opacity 1}})

      (let [pr (:result (create-pr* author (:id branch) {}))]

        ;; the branch keeps moving AFTER the pull request was created
        (apply-change* author bfid
                       {:type :add-color
                        :color {:id color-2 :name "Two" :color "#222222" :opacity 1}})

        (t/testing "the sandbox shows the reviewed state, not the live branch"
          (let [out    (th/command! {::th/type :get-pull-request-bundle
                                     ::rpc/profile-id (:id author)
                                     :id (:id pr)})
                result (:result out)
                colors (-> result :data :colors)]
            (t/is (nil? (:error out)))
            (t/is (contains? colors color-1))
            (t/is (not (contains? colors color-2)))
            ;; read-only by construction, whatever the caller's role is
            (t/is (false? (-> result :permissions :can-edit)))))

        (t/testing "the pull request reports the branch moved on"
          (let [out (th/command! {::th/type :get-pull-request
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (true? (-> out :result :outdated)))))

        (t/testing "publishing the branch state refreshes the sandbox"
          (let [out (th/command! {::th/type :update-pull-request-snapshot
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (nil? (:error out)))
            (t/is (true? (-> out :result :updated))))

          (let [out    (th/command! {::th/type :get-pull-request-bundle
                                     ::rpc/profile-id (:id author)
                                     :id (:id pr)})
                colors (-> out :result :data :colors)]
            (t/is (contains? colors color-2)))

          (let [out (th/command! {::th/type :get-pull-request
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (false? (-> out :result :outdated)))))

        (t/testing "publishing again with no new changes is a no-op"
          (let [out (th/command! {::th/type :update-pull-request-snapshot
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (nil? (:error out)))
            (t/is (false? (-> out :result :updated)))))))))

(t/deftest viewer-bundle-serves-the-pinned-snapshot
  (with-redefs [cf/flags pr-flags]
    (let [author  (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id author)
                                      :project-id (:default-project-id author)})
          branch  (create-branch* author file "feature")
          bfid    (:branch-file-id branch)
          color-1 (uuid/random)
          color-2 (uuid/random)]

      (apply-change* author bfid
                     {:type :add-color
                      :color {:id color-1 :name "One" :color "#111111" :opacity 1}})

      (let [pr (:result (create-pr* author (:id branch) {}))]

        ;; the branch keeps moving AFTER the pull request was created
        (apply-change* author bfid
                       {:type :add-color
                        :color {:id color-2 :name "Two" :color "#222222" :opacity 1}})

        ;; the bundle strips file :data down to pages/components, so the
        ;; served state is asserted through :revn (the overlay carries the
        ;; snapshot's revn; the live branch is one save ahead)
        (t/testing "the viewer bundle shows the reviewed state, read-only"
          (let [out    (th/command! {::th/type :get-view-only-bundle
                                     ::rpc/profile-id (:id author)
                                     :file-id bfid
                                     :pr-id (:id pr)})
                result (:result out)]
            (t/is (nil? (:error out)))
            (t/is (= (:review-revn pr) (-> result :file :revn)))
            (t/is (false? (-> result :permissions :can-edit)))))

        (t/testing "the viewer bundle without pr-id keeps the live state"
          (let [out (th/command! {::th/type :get-view-only-bundle
                                  ::rpc/profile-id (:id author)
                                  :file-id bfid})
                bf  (th/db-get :file {:id bfid})]
            (t/is (nil? (:error out)))
            (t/is (= (:revn bf) (-> out :result :file :revn)))
            (t/is (< (:review-revn pr) (:revn bf)))))

        (t/testing "the viewer bundle refuses a closed pull request"
          (th/command! {::th/type :close-pull-request
                        ::rpc/profile-id (:id author)
                        :id (:id pr)})
          (let [out   (th/command! {::th/type :get-view-only-bundle
                                    ::rpc/profile-id (:id author)
                                    :file-id bfid
                                    :pr-id (:id pr)})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :pull-request-not-open (-> error ex-data :code)))))))))

(t/deftest review-lifecycle
  (with-redefs [cf/flags pr-flags]
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
            pr     (:result (create-pr* author (:id branch)
                                        {:reviewers [(:id reviewer)]}))]

        (t/testing "a non-assigned member cannot submit a review"
          (let [out   (th/command! {::th/type :submit-pull-request-review
                                    ::rpc/profile-id (:id other)
                                    :id (:id pr)
                                    :state "approved"})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :not-a-reviewer (-> error ex-data :code)))))

        (t/testing "an approval flips the aggregate review state"
          (let [out (th/command! {::th/type :submit-pull-request-review
                                  ::rpc/profile-id (:id reviewer)
                                  :id (:id pr)
                                  :state "approved"
                                  :comment "ship it"})]
            (t/is (nil? (:error out))))
          (let [out (th/command! {::th/type :get-pull-request
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})
                row (:result out)]
            (t/is (= "approved" (:review-state row)))
            (t/is (false? (-> row :reviews first :stale)))))

        (t/testing "publishing new changes makes the verdict stale"
          (add-color* author bfid "Another")
          (let [out (th/command! {::th/type :update-pull-request-snapshot
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})]
            (t/is (nil? (:error out))))
          (let [row (:result (th/command! {::th/type :get-pull-request
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            ;; the verdict is kept for the history but no longer current
            (t/is (= "in-review" (:review-state row)))
            (t/is (true? (-> row :reviews first :stale)))))

        (t/testing "requesting changes beats an approval"
          (let [out (th/command! {::th/type :submit-pull-request-review
                                  ::rpc/profile-id (:id reviewer)
                                  :id (:id pr)
                                  :state "changes-requested"
                                  :comment "the second color is off-brand"})]
            (t/is (nil? (:error out))))
          (let [row (:result (th/command! {::th/type :get-pull-request
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            (t/is (= "changes-requested" (:review-state row)))))))))

(t/deftest update-pull-request-metadata-and-reviewers
  (with-redefs [cf/flags pr-flags]
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
            pr     (:result (create-pr* author (:id branch)
                                        {:reviewers [(:id reviewer-1)]}))]

        (t/testing "a plain editor (not author, not admin) cannot manage it"
          (let [out (th/command! {::th/type :update-pull-request
                                  ::rpc/profile-id (:id editor)
                                  :id (:id pr)
                                  :title "hijacked"})]
            (t/is (some? (:error out)))))

        (t/testing "the author can replace title, description and reviewers"
          (let [out (th/command! {::th/type :update-pull-request
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)
                                  :title "better title"
                                  :description "more context"
                                  :reviewers [(:id reviewer-2)]})]
            (t/is (nil? (:error out))))
          (let [row (:result (th/command! {::th/type :get-pull-request
                                           ::rpc/profile-id (:id author)
                                           :id (:id pr)}))]
            (t/is (= "better title" (:title row)))
            (t/is (= "more context" (:description row)))
            (t/is (= [(:id reviewer-2)] (mapv :profile-id (:reviews row))))))

        (t/testing "a removed reviewer can no longer submit"
          (let [out   (th/command! {::th/type :submit-pull-request-review
                                    ::rpc/profile-id (:id reviewer-1)
                                    :id (:id pr)
                                    :state "approved"})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :not-a-reviewer (-> error ex-data :code)))))))))

(t/deftest close-and-reopen-pull-request
  (with-redefs [cf/flags pr-flags]
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
            bfid   (:branch-file-id branch)
            pr     (:result (create-pr* author (:id branch) {}))]

        (t/testing "a plain editor cannot close someone else's pull request"
          (let [out (th/command! {::th/type :close-pull-request
                                  ::rpc/profile-id (:id editor)
                                  :id (:id pr)})]
            (t/is (some? (:error out)))))

        (t/testing "a team admin can close it"
          (let [out (th/command! {::th/type :close-pull-request
                                  ::rpc/profile-id (:id admin)
                                  :id (:id pr)})]
            (t/is (nil? (:error out)))
            (t/is (= "closed" (-> out :result :status)))))

        (t/testing "the sandbox stops resolving once closed"
          (let [out   (th/command! {::th/type :get-pull-request-bundle
                                    ::rpc/profile-id (:id author)
                                    :id (:id pr)})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :pull-request-not-open (-> error ex-data :code)))))

        (t/testing "the review snapshot pin is released on close"
          (let [rows (->> (th/db-query :file-change {:file-id bfid})
                          (filter #(some-> (:label %) (str/starts-with? "pr-review/"))))]
            (t/is (seq rows))
            (t/is (every? (fn [{:keys [deleted-at]}]
                            (and (some? deleted-at)
                                 (ct/is-before? deleted-at (ct/in-future {:days 400}))))
                          rows))))

        (t/testing "the author can reopen it, re-pinned at the current head"
          (add-color* author bfid "Late")
          (let [out (th/command! {::th/type :reopen-pull-request
                                  ::rpc/profile-id (:id author)
                                  :id (:id pr)})
                bf  (th/db-get :file {:id bfid})]
            (t/is (nil? (:error out)))
            (t/is (= "open" (-> out :result :status)))
            (t/is (= (:revn bf) (-> out :result :review-revn)))))))))

(t/deftest branch-lifecycle-closes-pull-requests
  (with-redefs [cf/flags pr-flags]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})]

      (t/testing "merging the branch marks the pull request merged"
        (let [branch (create-branch* author file "merge-me")
              _      (add-color* author (:branch-file-id branch) "Brand")
              pr     (:result (create-pr* author (:id branch) {}))
              out    (th/command! {::th/type :merge-file-branch
                                   ::rpc/profile-id (:id author)
                                   :branch-id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-pull-request {:id (:id pr)})]
            (t/is (= "merged" (:status row)))
            (t/is (some? (:closed-at row)))
            ;; the row survives as history even though the branch is gone
            (t/is (nil? (:deleted-at row))))))

      (t/testing "deleting the branch closes the pull request"
        (let [branch (create-branch* author file "delete-me")
              pr     (:result (create-pr* author (:id branch) {}))
              out    (th/command! {::th/type :delete-file-branch
                                   ::rpc/profile-id (:id author)
                                   :id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-pull-request {:id (:id pr)})]
            (t/is (= "closed" (:status row))))))

      (t/testing "archiving the branch closes the pull request"
        (let [branch (create-branch* author file "archive-me")
              pr     (:result (create-pr* author (:id branch) {}))
              out    (th/command! {::th/type :archive-file-branch
                                   ::rpc/profile-id (:id author)
                                   :id (:id branch)})]
          (t/is (nil? (:error out)))
          (let [[row] (th/db-query :file-pull-request {:id (:id pr)})]
            (t/is (= "closed" (:status row)))))))))

(t/deftest closed-pull-requests-listing
  (with-redefs [cf/flags pr-flags]
    (let [author (th/create-profile* 1 {:is-active true})
          file   (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
          branch (create-branch* author file "feature")
          pr     (:result (create-pr* author (:id branch) {}))]

      (th/command! {::th/type :close-pull-request
                    ::rpc/profile-id (:id author)
                    :id (:id pr)})

      (t/testing "closed pull requests are hidden by default"
        (let [out (th/command! {::th/type :get-file-pull-requests
                                ::rpc/profile-id (:id author)
                                :file-id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (= 0 (count (:result out))))))

      (t/testing "and visible with include-closed"
        (let [out (th/command! {::th/type :get-file-pull-requests
                                ::rpc/profile-id (:id author)
                                :file-id (:id file)
                                :include-closed true})]
          (t/is (nil? (:error out)))
          (t/is (= 1 (count (:result out))))
          (t/is (= "closed" (-> out :result first :status))))))))

(t/deftest branching-flag-gates-pull-request-commands
  (let [author (th/create-profile* 1 {:is-active true})
        file   (th/create-file* 1 {:profile-id (:id author)
                                   :project-id (:default-project-id author)})
        branch (with-redefs [cf/flags pr-flags]
                 (create-branch* author file "feature"))
        out    (create-pr* author (:id branch) {})
        error  (:error out)]
    (t/is (some? error))
    (t/is (= :branching-disabled (-> error ex-data :code)))))
