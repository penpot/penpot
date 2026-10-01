;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-branch-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-branch :as fbranch]
   [app.rpc.commands.files-branch-policies :as fbp]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]
   [promesa.exec.bulkhead :as pbh]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest create-and-list-branches
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          branch-file-id (volatile! nil)
          branch-meta-id (volatile! nil)]

      (t/testing "create branch"
        (let [params {::th/type :create-file-branch
                      ::rpc/profile-id (:id profile)
                      :file-id (:id file)
                      :name "redesign-checkout"
                      :description "A/B test de checkout"}
              out    (th/command! params)]
          ;; (th/print-result! out)
          (t/is (nil? (:error out)))
          (let [result (:result out)]
            (t/is (uuid? (:id result)))
            (t/is (uuid? (:branch-file-id result)))
            (t/is (= (:id file) (:source-file-id result)))
            (t/is (= "redesign-checkout" (:name result)))
            (t/is (= "open" (:status result)))
            (vreset! branch-file-id (:branch-file-id result))
            (vreset! branch-meta-id (:id result)))))

      (t/testing "branch file is flagged and hidden from project listing"
        (let [[row] (th/db-query :file {:id @branch-file-id})]
          (t/is (true? (:is-branch row))))

        (let [out (th/command! {::th/type :get-project-files
                                ::rpc/profile-id (:id profile)
                                :project-id proj-id})]
          (t/is (nil? (:error out)))
          (let [rows (:result out)
                ids  (set (map :id rows))
                src  (first (filter #(= (:id %) (:id file)) rows))]
            (t/is (contains? ids (:id file)))
            (t/is (not (contains? ids @branch-file-id)))
            ;; the source file card reports its open branch count
            (t/is (= 1 (:branches-count src))))))

      (t/testing "list branches"
        (let [out (th/command! {::th/type :get-file-branches
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)})]
          (t/is (nil? (:error out)))
          (let [[row :as result] (:result out)]
            (t/is (= 1 (count result)))
            (t/is (= "redesign-checkout" (:name row)))
            (t/is (= "open" (:status row)))
            (t/is (= 0 (:ahead row)))
            (t/is (= 0 (:behind row)))
            (t/is (= @branch-file-id (:branch-file-id row))))))

      (t/testing "branch context info"
        ;; the branch file reports its branch metadata
        (let [out (th/command! {::th/type :get-file-branch-info
                                ::rpc/profile-id (:id profile)
                                :file-id @branch-file-id})
              info (:result out)]
          (t/is (nil? (:error out)))
          (t/is (= "redesign-checkout" (:name info)))
          (t/is (= "open" (:status info)))
          (t/is (= (:id file) (:source-file-id info)))
          (t/is (= (:name file) (:source-name info)))
          (t/is (= 0 (:ahead info)))
          (t/is (= 0 (:behind info))))
        ;; the main file is not a branch -> nil
        (let [out (th/command! {::th/type :get-file-branch-info
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (nil? (:result out)))))

      (t/testing "merge base snapshot created on main"
        (let [rows (th/db-query :file-change {:file-id (:id file)})]
          (t/is (pos? (count rows)))
          (t/is (some #(= "system" (:created-by %)) rows))))

      (t/testing "diff right after creation has no conflicts"
        (let [out   (th/command! {::th/type :get-branch-diff
                                  ::rpc/profile-id (:id profile)
                                  :branch-id @branch-meta-id})
              stats (-> out :result :stats)
              meta  (-> out :result :meta)]
          (t/is (nil? (:error out)))
          (t/is (map? stats))
          ;; base == main == branch at fork -> nothing to merge, no conflicts
          (t/is (= 0 (:conflicts stats)))
          ;; the resolution UI gets the "when" of each side
          (t/is (some? (:main-at meta)))
          (t/is (some? (:branch-at meta)))
          (t/is (some? (:base-at meta)))))

      (t/testing "merge branch into main (clean, no-op)"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id @branch-meta-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))
        (let [[row] (th/db-query :file-branch {:id @branch-meta-id})]
          (t/is (= "merged" (:status row))))))))

(t/deftest listing-cache-makes-repeat-listing-free
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "cache-probe"}))
          branch-file-id (:branch-file-id create)
          main-page-id   (uuid/random)
          branch-page-id (uuid/random)]

      (t/testing "diverge both sides: one page on main, one on the branch"
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn mf)
                                :vern (:vern mf)
                                :features cfeat/supported-features
                                :changes [{:type :add-page :id main-page-id :name "main-side"}]})]
          (t/is (nil? (:error out)))
          (let [bf  (th/db-get :file {:id branch-file-id})
                out (th/command! {::th/type :update-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id
                                  :session-id (uuid/random)
                                  :revn (:revn bf)
                                  :vern (:vern bf)
                                  :features cfeat/supported-features
                                  :changes [{:type :add-page :id branch-page-id :name "branch-side"}]})]
            (t/is (nil? (:error out))))))

      (t/testing "cold listing computes, warm listing performs no comparison work"
        (let [orig-merge bm/compute-merge
              calls      (atom 0)]
          (with-redefs [bm/compute-merge (fn [& args] (swap! calls inc) (apply orig-merge args))]
            (let [cold (th/command! {::th/type :get-file-branches
                                     ::rpc/profile-id (:id profile)
                                     :file-id (:id file)})]
              (t/is (nil? (:error cold)))
              ;; both sides diverged: one forward pass, one reverse pass
              (t/is (= 2 @calls))
              (reset! calls 0)
              (let [warm (th/command! {::th/type :get-file-branches
                                       ::rpc/profile-id (:id profile)
                                       :file-id (:id file)})]
                (t/is (nil? (:error warm)))
                (t/is (= 0 @calls) "the warm listing performed comparison work")
                (t/is (= (mapv #(select-keys % [:id :name :ahead :behind :conflicts])
                               (:result cold))
                         (mapv #(select-keys % [:id :name :ahead :behind :conflicts])
                               (:result warm))))))))))))

(t/deftest merge-applies-branch-changes
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})

          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "feature-colors"}))
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)

          color-id (uuid/random)
          color    {:id color-id :name "Brand" :color "#ff0000" :opacity 1}]

      (t/testing "add a color on the branch"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes [{:type :add-color :color color}]})]
          (t/is (nil? (:error out)))))

      (t/testing "branch info reports diff-based ahead/behind"
        ;; one logical change on the branch (the added color), main untouched
        (let [info (:result (th/command! {::th/type :get-file-branch-info
                                          ::rpc/profile-id (:id profile)
                                          :file-id branch-file-id}))]
          (t/is (= 1 (:ahead info)))
          (t/is (= 0 (:behind info)))
          (t/is (= 0 (:conflicts info)))))

      (t/testing "branch list reports the same diff-based counts"
        (let [branches (:result (th/command! {::th/type :get-file-branches
                                              ::rpc/profile-id (:id profile)
                                              :file-id (:id file)}))
              row      (first (filter #(= branch-id (:id %)) branches))]
          (t/is (= 1 (:ahead row)))
          (t/is (= 0 (:behind row)))
          (t/is (= 0 (:conflicts row)))))

      (t/testing "merge brings the new color into main"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [out    (th/command! {::th/type :get-file
                                   ::rpc/profile-id (:id profile)
                                   :id (:id file)})
              colors (-> out :result :data :colors)]
          (t/is (nil? (:error out)))
          (t/is (contains? colors color-id))
          (t/is (= "Brand" (get-in colors [color-id :name]))))))))

(t/deftest update-branch-pulls-main-changes
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})

          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "long-lived"}))
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)

          color-id (uuid/random)
          color    {:id color-id :name "MainColor" :color "#00ff00" :opacity 1}]

      (t/testing "add a color on main (after the branch was created)"
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn mf)
                                :vern (:vern mf)
                                :features cfeat/supported-features
                                :changes [{:type :add-color :color color}]})]
          (t/is (nil? (:error out)))))

      (t/testing "compare in the :main->branch direction shows main's incoming change"
        (let [diff (:result (th/command! {::th/type :get-branch-diff
                                          ::rpc/profile-id (:id profile)
                                          :branch-id branch-id
                                          :direction :main->branch}))
              added (filterv #(= :added (:status %)) (:changes diff))]
          (t/is (= 1 (-> diff :stats :added)))
          (t/is (= color-id (-> added first :id)))))

      (t/testing "update the branch from main"
        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (let [out    (th/command! {::th/type :get-file
                                   ::rpc/profile-id (:id profile)
                                   :id branch-file-id})
              colors (-> out :result :data :colors)]
          (t/is (contains? colors color-id)))

        ;; merge base repositioned to the current state of main
        (let [[row] (th/db-query :file-branch {:id branch-id})
              mf    (th/db-get :file {:id (:id file)})]
          (t/is (= (:revn mf) (:base-revn row))))))))

(t/deftest merge-applies-page-add
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "feature-page"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          new-page-id    (uuid/random)]

      (t/testing "add a page on the branch"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes [{:type :add-page :id new-page-id :name "Branch Page"}]})]
          (t/is (nil? (:error out)))))

      (t/testing "merge brings the new page into main"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [out   (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id (:id file)})
              pages (-> out :result :data :pages-index)]
          (t/is (contains? pages new-page-id)))))))

(t/deftest merge-reparents-existing-shape
  ;; main: a rect at the page root. branch: that rect moved into a new board.
  ;; after merge main must have the rect INSIDE the board and NOT loose at
  ;; root (regression: reparenting was applied as :set parent-id, which left
  ;; the shape duplicated/loose).
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          page-id (-> (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id profile)
                                    :id (:id file)})
                      :result :data :pages first)
          rect-id (uuid/random)
          board-id (uuid/random)]

      (t/testing "add a rect at the root of main"
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn mf)
                                :vern (:vern mf)
                                :features cfeat/supported-features
                                :changes [{:type :add-obj
                                           :page-id page-id
                                           :id rect-id
                                           :parent-id uuid/zero
                                           :frame-id uuid/zero
                                           :obj (cts/setup-shape
                                                 {:id rect-id :name "Rect" :type :rect
                                                  :parent-id uuid/zero :frame-id uuid/zero})}]})]
          (t/is (nil? (:error out)))))

      (let [create  (:result (th/command! {::th/type :create-file-branch
                                           ::rpc/profile-id (:id profile)
                                           :file-id (:id file)
                                           :name "into-board"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        (t/testing "on the branch, move the rect into a new board"
          (let [bf  (th/db-get :file {:id branch-file-id})
                out (th/command! {::th/type :update-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id
                                  :session-id (uuid/random)
                                  :revn (:revn bf)
                                  :vern (:vern bf)
                                  :features cfeat/supported-features
                                  :changes [{:type :add-obj
                                             :page-id page-id
                                             :id board-id
                                             :parent-id uuid/zero
                                             :frame-id uuid/zero
                                             :obj (cts/setup-shape
                                                   {:id board-id :name "Board" :type :frame
                                                    :parent-id uuid/zero :frame-id uuid/zero})}
                                            {:type :mov-objects
                                             :page-id page-id
                                             :parent-id board-id
                                             :shapes [rect-id]
                                             :index 0}]})]
            (t/is (nil? (:error out)))))

        (t/testing "merge places the rect inside the board, not loose"
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status))))

          (let [objects (-> (th/command! {::th/type :get-file
                                          ::rpc/profile-id (:id profile)
                                          :id (:id file)})
                            :result :data :pages-index (get page-id) :objects)]
            ;; the board exists and contains the rect
            (t/is (contains? objects board-id))
            (t/is (= [rect-id] (get-in objects [board-id :shapes])))
            ;; the rect points to the board as its parent
            (t/is (= board-id (get-in objects [rect-id :parent-id])))
            ;; and is NOT left loose at the root
            (t/is (not (contains? (set (get-in objects [uuid/zero :shapes])) rect-id)))))))))

(t/deftest merge-component-stays-a-component
  ;; create a component on a branch, merge it into main, and verify the
  ;; merged main instance is still a valid component head (component-id set,
  ;; component-file re-pointed to main) — regression: it was detached.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          page-id (-> (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id profile)
                                    :id (:id file)})
                      :result :data :pages first)
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "with-component"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          comp-id (uuid/random)
          mi-id   (uuid/random)]

      (t/testing "create a component on the branch"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes
                                [{:type :add-obj
                                  :page-id page-id
                                  :id mi-id
                                  :parent-id uuid/zero
                                  :frame-id uuid/zero
                                  :obj (-> (cts/setup-shape
                                            {:id mi-id :name "Component 01" :type :frame
                                             :parent-id uuid/zero :frame-id uuid/zero})
                                           (assoc :component-root true
                                                  :main-instance true
                                                  :component-id comp-id
                                                  :component-file branch-file-id))}
                                 {:type :add-component
                                  :id comp-id
                                  :name "Component 01"
                                  :path ""
                                  :main-instance-id mi-id
                                  :main-instance-page page-id}]})]
          (t/is (nil? (:error out)))))

      (t/testing "merge integrates the component into main as a real head"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [data    (-> (th/command! {::th/type :get-file
                                        ::rpc/profile-id (:id profile)
                                        :id (:id file)})
                          :result :data)
              shape   (get-in data [:pages-index page-id :objects mi-id])]
          ;; the component registry row landed in main
          (t/is (contains? (:components data) comp-id))
          ;; the main instance is NOT detached: still a head pointing at main
          (t/is (= comp-id (:component-id shape)))
          (t/is (= (:id file) (:component-file shape)))
          (t/is (true? (:main-instance shape)))
          (t/is (true? (:component-root shape))))))))

(t/deftest branch-lifecycle
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "tmp"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)]

      (t/testing "rename"
        (let [out (th/command! {::th/type :update-file-branch
                                ::rpc/profile-id (:id profile)
                                :id branch-id
                                :name "renamed"})]
          (t/is (nil? (:error out))))
        (let [[row] (th/db-query :file-branch {:id branch-id})]
          (t/is (= "renamed" (:name row)))))

      (t/testing "archive hides it from the default list"
        (let [out (th/command! {::th/type :archive-file-branch
                                ::rpc/profile-id (:id profile)
                                :id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= "archived" (-> out :result :status))))
        (let [out (th/command! {::th/type :get-file-branches
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)})]
          (t/is (empty? (:result out))))
        (let [out (th/command! {::th/type :get-file-branches
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)
                                :include-archived true})]
          (t/is (= 1 (count (:result out))))))

      (t/testing "delete marks branch and branch file deleted"
        (let [out (th/command! {::th/type :delete-file-branch
                                ::rpc/profile-id (:id profile)
                                :id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :deleted (-> out :result :status))))
        (let [[row]  (th/db-query :file-branch {:id branch-id})
              [frow] (th/db-query :file {:id branch-file-id})]
          (t/is (some? (:deleted-at row)))
          (t/is (some? (:deleted-at frow))))))))

(t/deftest update-resolves-conflict-to-main
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          cid     (uuid/random)
          apply-change
          (fn [file-id change]
            (let [f (th/db-get :file {:id file-id})]
              (th/command! {::th/type :update-file
                            ::rpc/profile-id (:id profile)
                            :id file-id
                            :session-id (uuid/random)
                            :revn (:revn f)
                            :vern (:vern f)
                            :features cfeat/supported-features
                            :changes [change]})))]

      ;; color exists on main before branching (base = red)
      (apply-change (:id file) {:type :add-color :color {:id cid :name "Brand" :color "#ff0000" :opacity 1}})

      (let [create (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "b"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        ;; main edits the color -> green; branch edits it -> blue (conflict)
        (apply-change (:id file) {:type :mod-color :color {:id cid :name "Brand" :color "#00ff00" :opacity 1}})
        (apply-change branch-file-id {:type :mod-color :color {:id cid :name "Brand" :color "#0000ff" :opacity 1}})

        (t/testing "update without resolutions reports conflicts"
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :conflicts (-> out :result :status)))))

        (t/testing "resolving to main brings main's value into the branch"
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :resolutions {cid :main}})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))
          (let [out   (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id profile)
                                    :id branch-file-id})
                color (get-in out [:result :data :colors cid])]
            (t/is (= "#00ff00" (:color color)))))))))

(t/deftest branching-disabled-raises
  (let [profile (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id profile)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id proj-id
                                    :is-shared false})
        out     (th/command! {::th/type :create-file-branch
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)
                              :name "nope"})
        error   (:error out)
        data    (ex-data error)]
    (t/is (th/ex-info? error))
    (t/is (= :restriction (:type data)))
    (t/is (= :branching-disabled (:code data)))))

;;; --- Regression tests for the robustness fixes ---

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

(t/deftest merge-deletes-branch-by-default-and-releases-base
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "short-lived"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      ;; branch metadata and branch file logically deleted in the same tx
      (let [[row] (th/db-query :file-branch {:id branch-id})
            bf    (th/db-get :file {:id branch-file-id} {::db/remove-deleted false})]
        (t/is (= "merged" (:status row)))
        (t/is (some? (:deleted-at row)))
        (t/is (some? (:deleted-at bf))))

      ;; the pinned base snapshot is released: no branch-base row with a
      ;; multi-year retention remains
      (let [rows (->> (th/db-query :file-change {:file-id (:id file)})
                      (filter #(some-> (:label %) (str/starts-with? "branch-base/"))))]
        (t/is (seq rows))
        (t/is (every? (fn [{:keys [deleted-at]}]
                        (and (some? deleted-at)
                             (ct/is-before? deleted-at (ct/in-future {:days 400}))))
                      rows))))))

(t/deftest merge-with-keep-branch-archives-it
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "kept"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)]

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :keep-branch true})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [[row] (th/db-query :file-branch {:id branch-id})
            bf    (th/db-get :file {:id branch-file-id})]
        (t/is (= "merged" (:status row)))
        (t/is (nil? (:deleted-at row)))
        (t/is (nil? (:deleted-at bf)))))))

(t/deftest merge-requires-main-edition-permissions
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          proj-id  (:default-project-id profile1)
          file     (th/create-file* 1 {:profile-id (:id profile1)
                                       :project-id proj-id})
          create   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile1)
                                          :file-id (:id file)
                                          :name "perm-check"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)]

      ;; profile2 is a team viewer, editor of the BRANCH file but only a
      ;; viewer of main
      (th/create-team-role* {:team-id (:default-team-id profile1)
                             :profile-id (:id profile2)
                             :role :viewer})
      (th/create-file-role* {:file-id branch-file-id
                             :profile-id (:id profile2)
                             :role :editor})
      (th/create-file-role* {:file-id (:id file)
                             :profile-id (:id profile2)
                             :role :viewer})

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile2)
                              :branch-id branch-id})]
        (t/is (some? (:error out))))

      ;; but profile2 CAN update the branch from main (edits the branch)
      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile2)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))))))

(t/deftest merge-refuses-stale-expected-main-revn
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "stale"}))
          branch-id (:id create)]

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :expected-main-revn 9999})
            error (:error out)]
        (t/is (some? error))
        (t/is (= :file-modified (-> error ex-data :code)))))))

(t/deftest merge-refuses-typo-in-resolution-values
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          cid     (uuid/random)]

      ;; color exists on main before branching (base = red)
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id cid :name "Brand" :color "#ff0000" :opacity 1}})

      (let [create (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "typo"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        ;; main edits the color -> green; branch edits it -> blue (conflict)
        (apply-change* profile (:id file)
                       {:type :mod-color :color {:id cid :name "Brand" :color "#00ff00" :opacity 1}})
        (apply-change* profile branch-file-id
                       {:type :mod-color :color {:id cid :name "Brand" :color "#0000ff" :opacity 1}})

        (t/testing "a typo in a whole-entity resolution value is refused"
          (let [out   (th/command! {::th/type :merge-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-id branch-id
                                    :resolutions {cid :brnach}})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :validation (:type (ex-data error))))
            (t/is (= :params-validation (:code (ex-data error))))))

        (t/testing "a typo in a per-attr value is refused, not silently read as main"
          (let [out   (th/command! {::th/type :merge-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-id branch-id
                                    :resolutions {cid {:color :brnach}}})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :validation (:type (ex-data error))))
            ;; nothing was integrated: the branch is still open
            (t/is (= "open" (:status (first (th/db-query :file-branch {:id branch-id})))))))

        (t/testing "well-typed per-attr values still resolve"
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :resolutions {cid {:color :branch}}})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status))))
          (let [out (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id (:id file)})]
            (t/is (= "#0000ff" (get-in out [:result :data :colors cid :color])))))))))

(t/deftest update-refuses-stale-expected-main-revn
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "stale-update"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]

      ;; main advances after the branch was created
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id cid :name "M" :color "#00ff00" :opacity 1}})

      (t/testing "a stale token is refused and the branch is unchanged"
        (let [out   (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :expected-main-revn 9999})
              error (:error out)]
          (t/is (some? error))
          (t/is (= :file-modified (-> error ex-data :code))))
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (not (contains? (-> out :result :data :colors) cid)))))

      (t/testing "the current token lets the update through"
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :expected-main-revn (:revn mf)})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (contains? (-> out :result :data :colors) cid)))))))

(t/deftest revn-gate-survives-update-from-main
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "gate"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          bid (uuid/random)
          m1  (uuid/random)
          m2  (uuid/random)
          m3  (uuid/random)]

      ;; ONE change on the branch...
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id bid :name "B" :color "#0000ff" :opacity 1}})
      ;; ...while main advances MORE times than the branch (its revn ends up
      ;; far ahead of the branch's own revn counter)
      (doseq [[id nm] [[m1 "M1"] [m2 "M2"] [m3 "M3"]]]
        (apply-change* profile (:id file)
                       {:type :add-color :color {:id id :name nm :color "#00ff00" :opacity 1}}))

      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      ;; after repositioning the base, the branch's own unmerged change must
      ;; still be reported (before the fix, base-revn was compared against
      ;; the WRONG counter and ahead collapsed to 0)
      (let [info (:result (th/command! {::th/type :get-file-branch-info
                                        ::rpc/profile-id (:id profile)
                                        :file-id branch-file-id}))]
        (t/is (= 1 (:ahead info)))
        (t/is (= 0 (:behind info)))))))

(t/deftest cannot-branch-a-branch
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "level-1"}))]
      (let [out (th/command! {::th/type :create-file-branch
                              ::rpc/profile-id (:id profile)
                              :file-id (:branch-file-id create)
                              :name "level-2"})
            error (:error out)]
        (t/is (some? error))
        (t/is (= :cannot-branch-a-branch (-> error ex-data :code)))))))

(t/deftest deleting-source-cascades-to-branches
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "orphan-candidate"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          dt (ct/in-future {:days 7})]

      ;; run the delete-object cascade for the SOURCE file
      (th/run-task! :delete-object {:object :file :id (:id file) :deleted-at dt})

      (let [[row] (th/db-query :file-branch {:id branch-id})
            bf    (th/db-get :file {:id branch-file-id} {::db/remove-deleted false})]
        (t/is (some? (:deleted-at row)))
        (t/is (some? (:deleted-at bf)))))))

(t/deftest merge-copies-branch-media-into-main
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})

          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))

          mk-sobj (fn [content]
                    (sto/put-object! storage {::sto/content (sto/content content)
                                              :bucket :file-media-object
                                              :content-type "image/png"}))

          ;; media present on MAIN before branching (will be paired)
          sobj1  (mk-sobj "shared-image")
          fmo1   (th/create-file-media-object* {:file-id (:id file)
                                                :name "shared.png"
                                                :media-id (:id sobj1)})
          out1   (apply-change* profile (:id file)
                                {:type :add-media
                                 :object {:id (:id fmo1) :name "shared.png"
                                          :media-id (:id sobj1)
                                          :width 100 :height 100 :mtype "image/png"}})

          create (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :file-id (:id file)
                                        :name "with-media"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)

          ;; media ADDED on the branch after forking
          sobj2  (mk-sobj "branch-only-image")
          fmo2   (th/create-file-media-object* {:file-id branch-file-id
                                                :name "new.png"
                                                :media-id (:id sobj2)})
          out2   (apply-change* profile branch-file-id
                                {:type :add-media
                                 :object {:id (:id fmo2) :name "new.png"
                                          :media-id (:id sobj2)
                                          :width 100 :height 100 :mtype "image/png"}})]

      (t/testing "media changes were applied"
        (t/is (nil? (:error out1)))
        (t/is (nil? (:error out2)))
        (let [bf (th/db-get :file {:id branch-file-id})]
          (t/is (pos? (:revn bf)))))

      (t/testing "pre-existing media does not show up as branch changes"
        (let [info (:result (th/command! {::th/type :get-file-branch-info
                                          ::rpc/profile-id (:id profile)
                                          :file-id branch-file-id}))]
          ;; only the added media counts; the duplicated (paired) one is
          ;; normalized away instead of appearing as deleted+added churn
          (t/is (= 1 (:ahead info)))
          (t/is (= 0 (:behind info)))))

      (t/testing "merging brings the new media with a MAIN-owned row"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :keep-branch true})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [out   (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id (:id file)})
              media (-> out :result :data :media)
              added (->> (vals media) (filter #(= "new.png" (:name %))) first)]
          (t/is (some? added))
          ;; the merged entry references a row owned by MAIN (a copy), not
          ;; the branch's row (which dies with the branch file)
          (t/is (not= (:id fmo2) (:id added)))
          (let [row (th/db-get :file-media-object {:id (:id added)})]
            (t/is (= (:id file) (:file-id row)))
            (t/is (= (:id sobj2) (:media-id row)))))))))

(t/deftest merge-copies-colour-image-media-into-main
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})

          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))

          create (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :file-id (:id file)
                                        :name "colour-image"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)

          ;; media ADDED on the branch, used ONLY by a library colour: the
          ;; colorpicker upload registers no :media entry, the row is
          ;; referenced from the colour's :image id alone
          sobj   (sto/put-object! storage {::sto/content (sto/content "colour-image")
                                           :bucket :file-media-object
                                           :content-type "image/png"})
          fmo    (th/create-file-media-object* {:file-id branch-file-id
                                                :name "colour.png"
                                                :mtype "image/png"
                                                :media-id (:id sobj)})
          cid    (uuid/random)
          out    (apply-change* profile branch-file-id
                                {:type :add-color
                                 :color {:id cid :name "Brand"
                                         :image {:id (:id fmo)
                                                 :width 100 :height 100
                                                 :mtype "image/png"}
                                         :opacity 1}})]

      (t/is (nil? (:error out)))

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :keep-branch true})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [out    (th/command! {::th/type :get-file
                                 ::rpc/profile-id (:id profile)
                                 :id (:id file)})
            img-id (get-in out [:result :data :colors cid :image :id])]
        (t/is (some? img-id))
        ;; the colour's image is a row owned by MAIN (a copy), not the
        ;; branch's row (which dies with the branch file)
        (t/is (not= (:id fmo) img-id))
        (let [row (th/db-get :file-media-object {:id img-id})]
          (t/is (= (:id file) (:file-id row)))
          (t/is (= (:id sobj) (:media-id row))))))))

(t/deftest update-from-main-copies-colour-image-media-into-branch
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})

          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))

          create (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :file-id (:id file)
                                        :name "colour-image"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)

          ;; media ADDED on MAIN after forking, used only by a library colour
          sobj   (sto/put-object! storage {::sto/content (sto/content "main-colour-image")
                                           :bucket :file-media-object
                                           :content-type "image/png"})
          fmo    (th/create-file-media-object* {:file-id (:id file)
                                                :name "main-colour.png"
                                                :mtype "image/png"
                                                :media-id (:id sobj)})
          cid    (uuid/random)
          out    (apply-change* profile (:id file)
                                {:type :add-color
                                 :color {:id cid :name "Brand"
                                         :image {:id (:id fmo)
                                                 :width 100 :height 100
                                                 :mtype "image/png"}
                                         :opacity 1}})]

      (t/is (nil? (:error out)))

      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [out    (th/command! {::th/type :get-file
                                 ::rpc/profile-id (:id profile)
                                 :id branch-file-id})
            img-id (get-in out [:result :data :colors cid :image :id])]
        (t/is (some? img-id))
        ;; the colour's image keeps resolving to the right content (the
        ;; document names the branch's own paired copy: the base is pinned
        ;; in the branch's frame and the op log replays over it)
        (let [row (th/db-get :file-media-object {:id img-id})]
          (t/is (= (:id sobj) (:media-id row)))))

      (t/testing "the row is copied into the branch under a fresh id"
        (let [[copy] (th/db-query :file-media-object
                                  {:file-id branch-file-id
                                   :media-id (:id sobj)})]
          (t/is (some? copy))
          (t/is (not= (:id fmo) (:id copy)))))

      (t/testing "the update logs no phantom branch change"
        ;; the colour image id must be re-pointed at the copy like every
        ;; other ref (`bm/remap-changes`); left in main's frame it makes
        ;; the colour differ from the new base and lands in the op log
        (t/is (empty? (th/db-query :file-branch-change {:branch-id branch-id})))))))

(t/deftest materialize-branch-into-an-ordinary-file
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "exit-door"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          page-1         (uuid/random)
          page-2         (uuid/random)]

      (t/testing "a non-trivial op log: two saves on the branch"
        (doseq [[page-id nm] [[page-1 "log-one"] [page-2 "log-two"]]]
          (let [bf  (th/db-get :file {:id branch-file-id})
                out (th/command! {::th/type :update-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id
                                  :session-id (uuid/random)
                                  :revn (:revn bf)
                                  :vern (:vern bf)
                                  :features cfeat/supported-features
                                  :changes [{:type :add-page :id page-id :name nm}]})]
            (t/is (nil? (:error out)))))
        (t/is (= 2 (count (th/db-query :file-branch-change {:branch-id branch-id})))))

      (let [before (:result (th/command! {::th/type :get-file
                                          ::rpc/profile-id (:id profile)
                                          :id branch-file-id}))]

        (t/testing "materialize persists the derived state and drops the branch"
          (let [out (th/command! {::th/type :materialize-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-file-id branch-file-id})]
            (t/is (nil? (:error out)))
            (t/is (= :materialized (-> out :result :status)))
            (t/is (true? (-> out :result :changed))))

          (let [row (th/db-get :file {:id branch-file-id})]
            (t/is (false? (:is-branch row))))

          ;; the payload lands where the ordinary write path puts it, which
          ;; is the `file_data` row of type "main" rather than the legacy
          ;; `file.data` column
          (t/is (some? (th/db-get :file-data {:file-id branch-file-id :type "main"})))

          (t/is (empty? (th/db-query :file-branch-change {:branch-id branch-id})))

          ;; the branch row is archived AND logically deleted, so it has to
          ;; be read with the deleted-row filter off
          (let [row (th/db-get :file-branch {:id branch-id} {:app.db/remove-deleted false})]
            (t/is (= "archived" (:status row)))
            (t/is (some? (:deleted-at row)))))

        (t/testing "the file still opens with the state the log produced"
          (let [after (:result (th/command! {::th/type :get-file
                                             ::rpc/profile-id (:id profile)
                                             :id branch-file-id}))
                pages (-> after :data :pages-index)]
            (t/is (contains? pages page-1))
            (t/is (contains? pages page-2))
            (t/is (= (-> before :data :pages) (-> after :data :pages)))))

        (t/testing "the materialised file validates against its libraries"
          (let [errors (db/run! th/*system*
                                (fn [cfg]
                                  (let [f (bfc/get-file cfg branch-file-id :realize? true)]
                                    (cfv/validate-file f (bfc/get-resolved-file-libraries cfg f)))))]
            (t/is (empty? errors))))

        (t/testing "materializing again is a no-op"
          (let [out (th/command! {::th/type :materialize-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-file-id branch-file-id})]
            (t/is (nil? (:error out)))
            (t/is (= :materialized (-> out :result :status)))
            (t/is (false? (-> out :result :changed)))))))))

(defn- with-pruning-checked
  "Run `f` with the diff engine wrapped so that every pruned comparison is
  also computed whole-file and the two summaries must agree. Returns the
  number of pruned comparisons the run performed."
  [f]
  (let [orig   bm/compute-merge
        pruned (atom 0)]
    (with-redefs [bm/compute-merge
                  (fn [base main branch dir & [opts]]
                    (let [res (orig base main branch dir opts)]
                      (when (:only-pages opts)
                        (swap! pruned inc)
                        (t/is (= (orig base main branch dir nil) res)
                              "the pruned summary differs from the whole-file summary"))
                      res))]
      (f))
    @pruned))

(t/deftest pruned-diff-equals-the-whole-file-diff
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          page-id (-> (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id profile)
                                    :id (:id file)})
                      :result :data :pages first)
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "pruned"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          rect-id        (uuid/random)]

      (t/testing "a page-scoped edit on the branch and a page on main"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes [{:type :add-obj
                                           :page-id page-id
                                           :id rect-id
                                           :parent-id uuid/zero
                                           :frame-id uuid/zero
                                           :obj (cts/setup-shape
                                                 {:id rect-id :name "Pruned" :type :rect
                                                  :parent-id uuid/zero :frame-id uuid/zero})}]})]
          (t/is (nil? (:error out))))
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn mf)
                                :vern (:vern mf)
                                :features cfeat/supported-features
                                :changes [{:type :add-page :id (uuid/random) :name "main-side"}]})]
          (t/is (nil? (:error out)))))

      (t/testing "the compare is pruned and reports the same summary"
        (let [result (volatile! nil)
              pruned (with-pruning-checked
                       (fn []
                         (vreset! result (th/command! {::th/type :get-branch-diff
                                                       ::rpc/profile-id (:id profile)
                                                       :branch-id branch-id}))))]
          (t/is (nil? (:error @result)))
          (t/is (pos? pruned) "the compare was not pruned at all")
          (t/is (some #(= rect-id (:id %)) (:changes (:result @result))))))

      (t/testing "the listing is pruned and reports the same counts"
        (let [result (volatile! nil)
              pruned (with-pruning-checked
                       (fn []
                         (vreset! result (th/command! {::th/type :get-file-branches
                                                       ::rpc/profile-id (:id profile)
                                                       :file-id (:id file)}))))
              row    (first (:result @result))]
          (t/is (nil? (:error @result)))
          (t/is (pos? pruned))
          (t/is (= 1 (:ahead row)))
          (t/is (= 1 (:behind row))))))))

(t/deftest an-unscoped-log-keeps-the-whole-file-diff
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          create   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "unscoped"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          color-id (uuid/random)
          color    {:id color-id :name "Brand" :color "#ff0000" :opacity 1}]

      (t/testing "a file-level change on the branch: the reducer cannot name it"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes [{:type :add-color :color color}]})]
          (t/is (nil? (:error out)))))

      (t/testing "the compare falls back to the whole file and still sees it"
        (let [result (volatile! nil)
              pruned (with-pruning-checked
                       (fn []
                         (vreset! result (th/command! {::th/type :get-branch-diff
                                                       ::rpc/profile-id (:id profile)
                                                       :branch-id branch-id}))))]
          (t/is (nil? (:error @result)))
          (t/is (zero? pruned) "an unscoped log must not be pruned")
          (t/is (some #(= color-id (:id %)) (:changes (:result @result)))))))))

(t/deftest size-gates-refuse-and-name-the-limit
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})]

      (t/testing "the shape gate refuses branch creation, names the limit and creates nothing"
        (with-redefs [cf/get (th/config-get-mock {:branching-max-shapes 0})]
          (let [out  (th/command! {::th/type :create-file-branch
                                   ::rpc/profile-id (:id profile)
                                   :file-id (:id file)
                                   :name "too-many-shapes"})
                data (ex-data (:error out))]
            (t/is (some? (:error out)))
            (t/is (= :restriction (:type data)))
            (t/is (= :branching-shape-limit-exceeded (:code data)))
            (t/is (= 0 (:limit data)))
            (t/is (pos? (:actual data)))
            (t/is (str/includes? (:hint data) "materialise"))
            (t/is (empty? (th/db-query :file-branch {:source-file-id (:id file)}))))))

      (t/testing "the page gate refuses branch creation and names the limit"
        (with-redefs [cf/get (th/config-get-mock {:branching-max-pages 0})]
          (let [out  (th/command! {::th/type :create-file-branch
                                   ::rpc/profile-id (:id profile)
                                   :file-id (:id file)
                                   :name "too-many-pages"})
                data (ex-data (:error out))]
            (t/is (some? (:error out)))
            (t/is (= :restriction (:type data)))
            (t/is (= :branching-page-limit-exceeded (:code data)))
            (t/is (= 0 (:limit data)))
            (t/is (pos? (:actual data))))))

      (t/testing "the op-log depth gate refuses a compare and names the limit"
        (let [create (:result (th/command! {::th/type :create-file-branch
                                            ::rpc/profile-id (:id profile)
                                            :file-id (:id file)
                                            :name "deep-log"}))
              branch-id      (:id create)
              branch-file-id (:branch-file-id create)
              bf             (th/db-get :file {:id branch-file-id})
              _              (th/command! {::th/type :update-file
                                           ::rpc/profile-id (:id profile)
                                           :id branch-file-id
                                           :session-id (uuid/random)
                                           :revn (:revn bf)
                                           :vern (:vern bf)
                                           :features cfeat/supported-features
                                           :changes [{:type :add-page :id (uuid/random) :name "one"}]})]
          (with-redefs [cf/get (th/config-get-mock {:branching-max-oplog-changes 0})]
            (let [out  (th/command! {::th/type :get-branch-diff
                                     ::rpc/profile-id (:id profile)
                                     :branch-id branch-id})
                  data (ex-data (:error out))]
              (t/is (some? (:error out)))
              (t/is (= :restriction (:type data)))
              (t/is (= :branching-oplog-limit-exceeded (:code data)))
              (t/is (= 0 (:limit data)))
              (t/is (= 1 (:actual data)))
              (t/is (str/includes? (:hint data) "materialise")))))))))

(t/deftest published-limits-are-the-ones-the-gates-enforce
  (let [profile (th/create-profile* 1 {:is-active true})
        limits  (fn [] (th/command! {::th/type :get-branching-limits
                                     ::rpc/profile-id (:id profile)}))]

    (t/testing "the defaults are published"
      (with-redefs [cf/flags (conj cf/flags :branching)]
        (let [out (limits)]
          (t/is (nil? (:error out)))
          (t/is (= {:max-shapes 50000 :max-pages 500 :max-oplog-changes 100000}
                   (:result out))))))

    (t/testing "and they are read from config, not from the page"
      (with-redefs [cf/flags (conj cf/flags :branching)
                    cf/get (th/config-get-mock {:branching-max-shapes 7})]
        (t/is (= 7 (-> (limits) :result :max-shapes)))))

    (t/testing "the query is gated like every other branching command"
      (let [data (ex-data (:error (limits)))]
        (t/is (= :restriction (:type data)))
        (t/is (= :branching-disabled (:code data)))))))

(t/deftest branch-operations-record-duration-and-outcome
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          out     (th/command! {::th/type :create-file-branch
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)
                                :name "audited"})
          props   (-> out :result meta :app.loggers.audit/props)]

      (t/testing "creation carries its own audit props"
        (t/is (nil? (:error out)))
        (t/is (= "create-branch" (:branch-operation props)))
        (t/is (= "open" (:branch-outcome props)))
        (t/is (int? (:branch-duration-ms props))))

      (t/testing "a merge carries them too, with its outcome"
        (let [out   (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id (-> out :result :id)})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "merge" (:branch-operation props)))
          (t/is (= "merged" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))))

      (t/testing "a save routed to the branch reports the same three"
        ;; the ordinary save path replaces its props, so the branch keys
        ;; travel in `::audit/replace-props` and not in `::audit/props`
        (let [created  (th/command! {::th/type :create-file-branch
                                     ::rpc/profile-id (:id profile)
                                     :file-id (:id file)
                                     :name "audited-save"})
              bfid     (-> created :result :branch-file-id)
              file-row (th/db-get :file {:id bfid})
              out      (th/command! {::th/type :update-file
                                     ::rpc/profile-id (:id profile)
                                     :id bfid
                                     :session-id (uuid/random)
                                     :revn (:revn file-row)
                                     :vern (:vern file-row)
                                     :features cfeat/supported-features
                                     :changes [{:type :add-color
                                                :color {:id (uuid/random)
                                                        :name "audited"
                                                        :color "#112233"
                                                        :opacity 1}}]})
              props    (-> out :result meta :app.loggers.audit/replace-props)]
          (t/is (nil? (:error out)))
          (t/is (= "save-branch" (:branch-operation props)))
          (t/is (= "saved" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))
          (t/is (= bfid (:id props)))))

      (t/testing "materialising reports its own operation and outcome"
        (let [created (th/command! {::th/type :create-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :file-id (:id file)
                                    :name "audited-exit"})
              out     (th/command! {::th/type :materialize-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-file-id (-> created :result :branch-file-id)})
              props   (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "materialize" (:branch-operation props)))
          (t/is (= "materialized" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props))))))))

(t/deftest delete-serializes-against-a-concurrent-merge
  ;; the delete must take the advisory lock the merge takes and re-read
  ;; the branch under it: a merge that wins the race marks the branch
  ;; merged and deletes it, so a delete arriving afterwards must refuse
  ;; instead of deleting the branch a second time.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "contested"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})
      (let [orig-lock db/xact-lock!
            merging?  (atom false)
            merged    (atom nil)]
        (with-redefs [db/xact-lock!
                      (fn [conn id]
                        ;; the first lock the delete takes is the signal:
                        ;; merge the same branch through the pool in its
                        ;; own transaction BEFORE the delete locks
                        (when (compare-and-set! merging? false true)
                          (reset! merged (th/command! {::th/type :merge-file-branch
                                                       ::rpc/profile-id (:id profile)
                                                       :branch-id branch-id})))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :delete-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :id branch-id})]
            (t/is (= :branch-not-found (-> out :error ex-data :code)))
            (t/is (= :merged (-> @merged :result :status)))
            ;; the merge's deletion is the only one: the row still says
            ;; the branch was merged
            (let [[row] (th/db-query :file-branch {:id branch-id})]
              (t/is (= "merged" (:status row)))
              (t/is (some? (:deleted-at row))))))))))

(t/deftest delete-keeps-the-status-the-branch-carries
  ;; deleting a kept merged branch must not rewrite its status: the
  ;; record is the only place left that says the branch was merged
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "kept"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})
      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :keep-branch true})]
        (t/is (= :merged (-> out :result :status))))
      (let [out (th/command! {::th/type :delete-file-branch
                              ::rpc/profile-id (:id profile)
                              :id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :deleted (-> out :result :status))))
      (let [[row] (th/db-query :file-branch {:id branch-id})]
        (t/is (= "merged" (:status row)))
        (t/is (some? (:deleted-at row)))))))

(t/deftest rename-reaches-the-branch-file-name
  ;; the branch file carries its own name (set from the branch name at
  ;; creation), so a rename that changes only the branch row leaves the
  ;; views that show the file's name on the old one
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "old-name"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)]

      (let [out (th/command! {::th/type :update-file-branch
                              ::rpc/profile-id (:id profile)
                              :id branch-id
                              :name "new-name"})]
        (t/is (nil? (:error out))))

      (let [[frow] (th/db-query :file {:id branch-file-id})]
        (t/is (= "new-name" (:name frow)))))))

(t/deftest small-branch-mutations-record-duration-and-outcome
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "audited"}))
          branch-id (:id create)]

      (t/testing "a rename carries its operation, outcome and duration"
        (let [out   (th/command! {::th/type :update-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :id branch-id
                                  :name "audited-rename"})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "rename-branch" (:branch-operation props)))
          (t/is (= "ok" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))))

      (t/testing "archiving reports archive-branch, restoring restore-branch"
        (let [out   (th/command! {::th/type :archive-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :id branch-id})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "archive-branch" (:branch-operation props)))
          (t/is (= "archived" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props))))
        (let [out   (th/command! {::th/type :archive-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :id branch-id
                                  :archived false})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "restore-branch" (:branch-operation props)))
          (t/is (= "open" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))))

      (t/testing "a delete reports delete-branch and its outcome"
        (let [out   (th/command! {::th/type :delete-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :id branch-id})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "delete-branch" (:branch-operation props)))
          (t/is (= "deleted" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props))))))))

(t/deftest base-at-follows-the-repositioned-base
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "meta"}))
          branch-id (:id create)]

      ;; main moves after the fork...
      (apply-change* profile (:id file)
                     {:type :add-color
                      :color {:id (uuid/random) :name "M" :color "#00ff00" :opacity 1}})

      ;; ...and the update repositions the merge base to a new snapshot
      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [brow (first (th/db-query :file-branch {:id branch-id}))
            base (first (th/db-query :file-change {:id (:base-snapshot-id brow)}))
            meta (:meta (:result (th/command! {::th/type :get-branch-diff
                                               ::rpc/profile-id (:id profile)
                                               :branch-id branch-id})))]
        ;; `:base-at` is when the CURRENT base snapshot row was created...
        (t/is (= (:created-at base) (:base-at meta)))
        ;; ...which is after the branch was created
        (t/is (ct/is-after? (:base-at meta) (:created-at brow)))))))

;;; --- The validator decides what the repair leaves behind

(defn- synthetic-validation-error
  "One validation error that no repair stub fixes."
  [file]
  [{:code :parent-not-found
    :hint "synthetic validation error"
    :file-id (:id file)
    :shape-id (uuid/random)}])

(defn- validate-once
  "Stub for `cfv/validate-file` that reports the synthetic error on the
  first call and nothing on the calls after it, so the repair runs and
  what it leaves behind is only seen by the re-validation."
  []
  (let [calls (atom 0)]
    (fn [file _libs]
      (when (= 1 (swap! calls inc))
        (synthetic-validation-error file)))))

(defn- newest-xlog
  "Decode the changes of the newest change-log (xlog) row written for a
  file. Snapshot rows carry no changes and are skipped."
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id})
       (filter #(some? (:changes %)))
       (sort-by :revn)
       (last)
       (:changes)
       (blob/decode)))

(t/deftest merge-and-update-refuse-an-unrepairable-result
  ;; the validator, not the merge, decides: what the repair cannot fix
  ;; refuses the operation and the transaction rolls back instead of
  ;; being persisted as if the repair had fixed it
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "unrepairable"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          branch-color   (uuid/random)
          main-color     (uuid/random)

          ;; validation reports an error the repair cannot fix: the
          ;; repair produces no changes and the re-validation still
          ;; reports the same error
          unfixable (fn [file _libs] (synthetic-validation-error file))
          no-repair (fn [_file _libs _errors] [])]

      (t/testing "the merge refuses and leaves main and the branch untouched"
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id branch-color :name "B" :color "#112233" :opacity 1}})

        (with-redefs [cfv/validate-file unfixable
                      cfr/repair-file no-repair]
          (let [out  (th/command! {::th/type :merge-file-branch
                                   ::rpc/profile-id (:id profile)
                                   :branch-id branch-id})
                data (ex-data (:error out))]
            (t/is (some? (:error out)))
            (t/is (= :validation (:type data)))
            (t/is (= :merge-result-invalid (:code data)))
            (t/is (= [:parent-not-found] (:codes data)))
            (t/is (some? (:hint data)))))

        (let [colors (-> (th/command! {::th/type :get-file
                                       ::rpc/profile-id (:id profile)
                                       :id (:id file)})
                         :result :data :colors)]
          (t/is (empty? colors)))

        (let [[row] (th/db-query :file-branch {:id branch-id})]
          (t/is (= "open" (:status row)))))

      (t/testing "the update refuses and leaves the branch untouched"
        (apply-change* profile (:id file)
                       {:type :add-color
                        :color {:id main-color :name "M" :color "#445566" :opacity 1}})

        (let [[base] (th/db-query :file-branch {:id branch-id})]
          (with-redefs [cfv/validate-file unfixable
                        cfr/repair-file no-repair]
            (let [out  (th/command! {::th/type :update-branch-from-main
                                     ::rpc/profile-id (:id profile)
                                     :branch-id branch-id})
                  data (ex-data (:error out))]
              (t/is (some? (:error out)))
              (t/is (= :validation (:type data)))
              (t/is (= :update-result-invalid (:code data)))
              (t/is (= [:parent-not-found] (:codes data)))))

          (let [colors (-> (th/command! {::th/type :get-file
                                         ::rpc/profile-id (:id profile)
                                         :id branch-file-id})
                           :result :data :colors)]
            (t/is (contains? colors branch-color))
            (t/is (not (contains? colors main-color))))

          (let [[row] (th/db-query :file-branch {:id branch-id})]
            (t/is (= "open" (:status row)))
            (t/is (= (:base-revn base) (:base-revn row))))))

      (t/testing "materialize never refuses: the exit door lets the user out"
        (with-redefs [cfv/validate-file unfixable
                      cfr/repair-file no-repair]
          (let [out (th/command! {::th/type :materialize-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-file-id branch-file-id})]
            (t/is (nil? (:error out)))
            (t/is (= :materialized (-> out :result :status)))
            (t/is (true? (-> out :result :changed)))))

        (let [row (th/db-get :file {:id branch-file-id})]
          (t/is (false? (:is-branch row))))))))

(t/deftest the-change-log-carries-the-repair-changes
  ;; the xlog row records what was applied to the file: the computed
  ;; changes AND the repair changes, in application order. A client that
  ;; catches up through lagged changes must end up with the stored file.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "logged-repair"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          page-id        (uuid/random)

          ;; the repair renames the file's first page: one known change,
          ;; captured so the log can be compared against it. The name is
          ;; per scenario so the first scenario's repair (which the
          ;; update's squash turns into a branch op) cannot stand in for
          ;; the second one's repair
          repairs (atom [])
          repair! (fn [name]
                    (fn [file _libs _errors]
                      (let [change {:type :mod-page
                                    :id (first (get-in file [:data :pages]))
                                    :name name}]
                        (swap! repairs conj change)
                        [change])))]

      (t/testing "the update's xlog row carries the repair changes"
        (apply-change* profile (:id file)
                       {:type :add-color
                        :color {:id (uuid/random) :name "M" :color "#445566" :opacity 1}})

        (with-redefs [cfv/validate-file (validate-once)
                      cfr/repair-file (repair! "repaired-update")]
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status)))))

        (t/is (some #(= (first @repairs) %) (newest-xlog branch-file-id))))

      (t/testing "the merge's xlog row carries the repair changes"
        (reset! repairs [])
        (apply-change* profile branch-file-id
                       {:type :add-page :id page-id :name "from-branch"})

        (with-redefs [cfv/validate-file (validate-once)
                      cfr/repair-file (repair! "repaired-merge")]
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status)))))

        (t/is (some #(= (first @repairs) %) (newest-xlog (:id file))))))))

(t/deftest merge-update-and-materialize-invalidate-the-summary-cache
  ;; the file summary cache is dropped by every write path; the branch
  ;; writes are write paths too or the summary describes the pre-merge
  ;; file until the next ordinary save
  (with-redefs [cf/flags (conj cf/flags :branching :redis-cache)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          ;; random file ids: the cache outlives the test database reset,
          ;; and a deterministic id would carry a previous run's summary
          file    (th/create-file* 1 {:id (uuid/random)
                                      :profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "cache-merge"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)

          file2   (th/create-file* 2 {:id (uuid/random)
                                      :profile-id (:id profile)
                                      :project-id proj-id})
          create2 (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file2)
                                         :name "cache-update"}))
          b2-id   (:id create2)
          b2-fid  (:branch-file-id create2)

          summary (fn [id]
                    (:result (th/command! {::th/type :get-file-summary
                                           ::rpc/profile-id (:id profile)
                                           :id id})))]

      (t/testing "the merge drops the cached summary of main"
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id (uuid/random) :name "B" :color "#112233" :opacity 1}})

        ;; the cache now describes the pre-merge main
        (t/is (= 0 (-> (summary (:id file)) :colors :count)))

        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (t/is (= 1 (-> (summary (:id file)) :colors :count))))

      (t/testing "the update drops the cached summary of the branch"
        (apply-change* profile (:id file2)
                       {:type :add-color
                        :color {:id (uuid/random) :name "M" :color "#445566" :opacity 1}})

        ;; the cache now describes the pre-update branch
        (t/is (= 0 (-> (summary b2-fid) :colors :count)))

        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id b2-id})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (t/is (= 1 (-> (summary b2-fid) :colors :count))))

      (t/testing "materialize drops the cached summary of the file"
        ;; materializing alone persists what the file already derives, so
        ;; the repair is what makes the stored state differ from the
        ;; cached summary
        (with-redefs [cfv/validate-file (validate-once)
                      cfr/repair-file (fn [_file _libs _errors]
                                        [{:type :add-color
                                          :color {:id (uuid/random)
                                                  :name "R"
                                                  :color "#778899"
                                                  :opacity 1}}])]
          (let [out (th/command! {::th/type :materialize-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-file-id b2-fid})]
            (t/is (nil? (:error out)))
            (t/is (= :materialized (-> out :result :status)))))

        (t/is (= 2 (-> (summary b2-fid) :colors :count)))))))

;;; --- The branch row is re-read under the advisory lock
;;
;; The commands read the `file_branch` row before `db/tx-run!`. A
;; command that commits between that read and the advisory lock leaves
;; the stale row in play, so the row is re-read under the lock and only
;; that one is trusted. Each interleaving below runs the competing
;; command through the pool (its own transaction, committed) on the
;; FIRST `db/xact-lock!` call of the outer command, i.e. before the
;; outer one holds any lock.
;;
;; The RPC concurrency limiter caps `:merge-file-branch/global` and
;; `:update-branch-from-main/global` at one permit each, and would
;; serialize two same-kind commands completely (the nested one could
;; never run inside the outer one's transaction), hiding the lock-level
;; race these tests target; the interleaved tests run with the bulkhead
;; transparent.

(defn- transparent-climit
  "The bulkhead made transparent (see the section note above)."
  [_limiter handler]
  (handler))

(t/deftest merge-refuses-a-branch-merged-under-the-lock
  ;; two merges of one open branch: the winner commits before the
  ;; outer merge takes any lock. The outer merge must refuse instead of
  ;; trusting its stale "open" row and deleting the branch the winner
  ;; kept.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "contested"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})
      (let [orig-lock db/xact-lock!
            raced?    (atom false)
            merged    (atom nil)]
        (with-redefs [pbh/invoke! transparent-climit
                      db/xact-lock!
                      (fn [conn id]
                        ;; the winning merge runs to completion BEFORE
                        ;; the outer one takes any lock, and keeps the
                        ;; branch so the outer merge still has its file
                        (when (compare-and-set! raced? false true)
                          (reset! merged (th/command! {::th/type :merge-file-branch
                                                       ::rpc/profile-id (:id profile)
                                                       :branch-id branch-id
                                                       :keep-branch true})))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :branch-not-open (-> out :error ex-data :code)))))
        (t/is (= :merged (-> @merged :result :status)))
        (t/testing "main holds the first merge's revn only"
          (t/is (= (-> @merged :result :revn)
                   (:revn (first (th/db-query :file {:id (:id file)}))))))
        (t/testing "the branch the winning merge kept is still kept"
          (let [[row] (th/db-query :file-branch {:id branch-id})]
            (t/is (= "merged" (:status row)))
            (t/is (nil? (:deleted-at row)))))))))

(t/deftest merge-releases-the-base-the-lock-shows
  ;; the merge releases the base snapshot pin (on the delete path), and
  ;; the id must come from the row as it stands under the lock: an
  ;; update-from-main that repositions the base while the merge waits
  ;; must not leave the new base pinned for ten years while the
  ;; superseded one is released a second time
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "base-race"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          old-base       (:base-snapshot-id (first (th/db-query :file-branch {:id branch-id})))
          cid (uuid/random)
          mid (uuid/random)]
      ;; the branch carries its own change and main moves ahead, so the
      ;; competing update really repositions the merge base
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id mid :name "M" :color "#332211" :opacity 1}})
      (let [orig-lock  db/xact-lock!
            raced?     (atom false)
            superseded (atom nil)]
        (with-redefs [db/xact-lock!
                      (fn [conn id]
                        ;; before the merge takes any lock, an update
                        ;; from main repositions the merge base and
                        ;; releases the pin the branch carried so far
                        (when (compare-and-set! raced? false true)
                          (t/is (nil? (:error (th/command! {::th/type :update-branch-from-main
                                                            ::rpc/profile-id (:id profile)
                                                            :branch-id branch-id}))))
                          (reset! superseded
                                  (:deleted-at (first (th/db-query :file-change {:id old-base})))))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status)))))
        (let [brow (first (th/db-query :file-branch {:id branch-id}))
              cur  (first (th/db-query :file-change {:id (:base-snapshot-id brow)}))
              old  (first (th/db-query :file-change {:id old-base}))]
          (t/is (not= old-base (:base-snapshot-id brow)))
          ;; the merge released the CURRENT base snapshot...
          (t/is (some? (:deleted-at cur)))
          (t/is (ct/is-before? (:deleted-at cur) (ct/in-future {:days 400})))
          ;; ...and left the superseded one where the update left it
          (t/is (= @superseded (:deleted-at old))))))))

(t/deftest update-releases-the-base-the-lock-shows
  ;; the same stale base id on the update path: the second of two
  ;; overlapping updates from main must release the base the first one
  ;; created, not the one it superseded
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "update-race"}))
          branch-id (:id create)
          old-base  (:base-snapshot-id (first (th/db-query :file-branch {:id branch-id})))
          mid (uuid/random)]
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id mid :name "M" :color "#332211" :opacity 1}})
      (let [orig-lock  db/xact-lock!
            raced?     (atom false)
            first-base (atom nil)
            superseded (atom nil)]
        (with-redefs [pbh/invoke! transparent-climit
                      db/xact-lock!
                      (fn [conn id]
                        ;; the first update runs to completion BEFORE
                        ;; the second one takes any lock: it repositions
                        ;; the merge base and releases the old pin
                        (when (compare-and-set! raced? false true)
                          (t/is (nil? (:error (th/command! {::th/type :update-branch-from-main
                                                            ::rpc/profile-id (:id profile)
                                                            :branch-id branch-id}))))
                          (let [brow (first (th/db-query :file-branch {:id branch-id}))]
                            (reset! first-base (:base-snapshot-id brow))
                            (reset! superseded
                                    (:deleted-at (first (th/db-query :file-change {:id old-base}))))))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :updated (-> out :result :status)))))
        (let [brow (first (th/db-query :file-branch {:id branch-id}))
              cur  (first (th/db-query :file-change {:id (:base-snapshot-id brow)}))
              prev (first (th/db-query :file-change {:id @first-base}))
              old  (first (th/db-query :file-change {:id old-base}))]
          (t/is (not= @first-base (:base-snapshot-id brow)))
          ;; the second update released the base the first one created
          (t/is (some? (:deleted-at prev)))
          (t/is (ct/is-before? (:deleted-at prev) (ct/in-future {:days 400})))
          ;; the superseded one is not released twice...
          (t/is (= @superseded (:deleted-at old)))
          ;; ...and the base the branch now points at stays pinned
          (t/is (not (ct/is-before? (:deleted-at cur) (ct/in-future {:days 400})))))))))

;;; --- Messages leave only after the transaction commits
;;
;; A message sent from inside the transaction tells clients about state
;; they cannot read yet, and a rolled back transaction would tell them
;; about something that never happened. The stub below records, at
;; publish time, what ANOTHER connection sees.

(t/deftest delete-notifies-only-after-the-commit
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "notify"}))
          branch-id (:id create)
          seen (atom [])]
      (with-redefs [mbus/pub!
                    (fn [_ & {:keys [topic message]}]
                      (swap! seen conj {:topic topic
                                        :message message
                                        :branch (first (th/db-query :file-branch {:id branch-id}))}))]
        (let [out (th/command! {::th/type :delete-file-branch
                                ::rpc/profile-id (:id profile)
                                :id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :deleted (-> out :result :status)))))
      (let [[{:keys [message branch]}] @seen]
        (t/is (= 1 (count @seen)))
        (t/is (= :file-deleted (:type message)))
        ;; the deletion was already committed when the message left
        (t/is (some? (:deleted-at branch)))))))

(t/deftest merge-notifies-only-after-the-commit
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "notify-merge"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)
          seen (atom [])]
      (t/is (nil? (:error (apply-change* profile branch-file-id
                                         {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}}))))
      (with-redefs [mbus/pub!
                    (fn [_ & {:keys [message]}]
                      (swap! seen conj {:message message
                                        :main-revn (:revn (first (th/db-query :file {:id (:id file)})))}))]
        ;; a refused merge publishes nothing
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :expected-main-revn 9999})]
          (t/is (= :file-modified (-> out :error ex-data :code)))
          (t/is (empty? @seen)))
        ;; the kept branch means :file-merged is the only message
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :keep-branch true})]
          (t/is (= :merged (-> out :result :status))))
        (let [[{:keys [message main-revn]}] @seen]
          (t/is (= 1 (count @seen)))
          (t/is (= :file-merged (:type message)))
          ;; the merge was already committed when the message left
          (t/is (= (:revn message) main-revn)))))))

(t/deftest update-notifies-only-after-the-commit
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "notify-update"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          mid (uuid/random)
          seen (atom [])]
      (t/is (nil? (:error (apply-change* profile (:id file)
                                         {:type :add-color :color {:id mid :name "M" :color "#332211" :opacity 1}}))))
      (with-redefs [mbus/pub!
                    (fn [_ & {:keys [topic message]}]
                      (swap! seen conj {:topic topic
                                        :message message
                                        :branch-revn (:revn (first (th/db-query :file {:id branch-file-id})))}))]
        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (= :updated (-> out :result :status))))
        (let [[{:keys [topic message branch-revn]}] @seen]
          (t/is (= 1 (count @seen)))
          (t/is (= :file-merged (:type message)))
          (t/is (= branch-file-id topic))
          ;; the update was already committed when the message left
          (t/is (= (:revn message) branch-revn)))))))

(t/deftest materialize-takes-the-branch-file-id
  ;; `:branch-file-id` names the branch FILE — the id `::create-file-branch`
  ;; returns under that same key — while the sibling commands take the
  ;; `file_branch` row id. The old generic `:file-id` name is refused by
  ;; the schema.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile        (th/create-profile* 1 {:is-active true})
          proj-id        (:default-project-id profile)
          file           (th/create-file* 1 {:profile-id (:id profile)
                                             :project-id proj-id
                                             :is-shared false})
          create         (:result (th/command! {::th/type :create-file-branch
                                                ::rpc/profile-id (:id profile)
                                                :file-id (:id file)
                                                :name "to-materialize"}))
          branch-file-id (:branch-file-id create)]

      (t/testing "the old :file-id param is refused by the schema"
        (let [{:keys [error]} (th/command! {::th/type :materialize-file-branch
                                            ::rpc/profile-id (:id profile)
                                            :file-id branch-file-id})]
          (t/is (some? error))
          (t/is (th/ex-of-type? error :validation))
          (t/is (th/ex-of-code? error :params-validation)))
        ;; refused before the handler runs, so the branch is untouched
        (t/is (true? (:is-branch (th/db-get :file {:id branch-file-id})))))

      (t/testing "materialize runs on :branch-file-id"
        (let [{:keys [error result]} (th/command! {::th/type :materialize-file-branch
                                                   ::rpc/profile-id (:id profile)
                                                   :branch-file-id branch-file-id})]
          (t/is (nil? error))
          (t/is (= :materialized (:status result)))
          (t/is (true? (:changed result))))))))

;;; --- A comparison that cannot be made is never "in sync"
;;
;; The listing is read-only: it cannot repair anything, and merge and
;; update DO refuse loudly when the merge base cannot be resolved. So
;; the row carries the error marker and no counts at all: zeros read as
;; "in sync", and a branch that cannot be compared is not in sync.

(t/deftest missing-base-snapshot-lists-an-error-marker
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "lost-base"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)]

      ;; the branch diverged, so the listing pays for the comparison
      (t/is (nil? (:error (apply-change* profile branch-file-id
                                         {:type :add-color
                                          :color {:id (uuid/random)
                                                  :name "Brand"
                                                  :color "#ff0000"
                                                  :opacity 1}}))))

      ;; the merge-base snapshot is gone
      (let [base (:base-snapshot-id (first (th/db-query :file-branch {:id branch-id})))]
        (t/is (some? base))
        (th/db-delete! :file-change {:id base})
        (t/is (empty? (th/db-query :file-change {:id base}))))

      (let [out (th/command! {::th/type :get-file-branches
                              ::rpc/profile-id (:id profile)
                              :file-id (:id file)})
            row (first (filter #(= branch-id (:id %)) (:result out)))]
        (t/is (nil? (:error out)))
        (t/is (some? row))
        (t/is (true? (:diff-error row)) "the row does not carry the error marker")
        (t/is (nil? (:ahead row)))
        (t/is (nil? (:behind row)))
        (t/is (nil? (:conflicts row)))))))

;;; --- The listing holds no pooled connection across the comparisons
;;
;; Each comparison takes its own connection and returns it before the
;; next one starts: the listing query uses one, the comparisons use one
;; each, and none of them is held while the others run.

(t/deftest listing-compare-takes-a-connection-per-diff
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          create*  (fn [name]
                     (:result (th/command! {::th/type :create-file-branch
                                            ::rpc/profile-id (:id profile)
                                            :file-id (:id file)
                                            :name name})))
          branches [(create* "one") (create* "two")]]

      ;; both branches diverged, so the listing compares both
      (doseq [branch branches]
        (t/is (nil? (:error (apply-change* profile (:branch-file-id branch)
                                           {:type :add-color
                                            :color {:id (uuid/random)
                                                    :name "Brand"
                                                    :color "#ff0000"
                                                    :opacity 1}})))))

      (let [orig-open    db/open
            orig-compute fbranch/branch-diff-counts!
            checkouts    (atom [])
            runs         (atom [])]
        (with-redefs [db/open
                      (fn [system-or-pool]
                        (let [conn (orig-open system-or-pool)]
                          (swap! checkouts conj conn)
                          conn))

                      fbranch/branch-diff-counts!
                      (fn [cfg main-data branch]
                        (let [conn (db/get-connection cfg)
                              prev (:conn (peek @runs))]
                          (swap! runs conj
                                 {:conn conn
                                  ;; the previous comparison's connection
                                  ;; is back in the pool by now
                                  :prev-closed? (or (nil? prev)
                                                    (.isClosed ^java.sql.Connection prev))
                                  ;; every connection handed out so far and
                                  ;; still checked out at this moment
                                  :held (into []
                                              (remove #(.isClosed ^java.sql.Connection %))
                                              @checkouts)}))
                        (orig-compute cfg main-data branch))]
          (t/is (nil? (:error (th/command! {::th/type :get-file-branches
                                            ::rpc/profile-id (:id profile)
                                            :file-id (:id file)})))))

        (t/is (= 2 (count @runs)) "both branches were compared")
        ;; every comparison runs with a connection of its own
        (t/is (apply distinct? (map :conn @runs))
              "two comparisons shared one connection")
        (t/is (every? :prev-closed? (rest @runs))
              "a connection is held across the comparisons")
        ;; ...and while it runs, its own is the only one checked out: the
        ;; listing's and the previous comparison's are back in the pool
        (t/is (every? (fn [{:keys [conn held]}] (= [conn] held)) @runs)
              "a connection is held while the comparisons run")))))

;;; --- Media pairing: the branch holds its own copies

(defn- squashed-log-types
  "Change types the branch's op log (`file_branch_change`) carries after
  the squash an update-from-main performs."
  [branch-id]
  (into []
        (mapcat (fn [row] (map :type (blob/decode (:changes row)))))
        (th/db-query :file-branch-change {:branch-id branch-id})))

(def ^:private media-change-types
  #{:add-media :mod-media :del-media})

(t/deftest update-from-main-deletes-the-paired-media-copy
  ;; main deletes a media object the branch holds as a paired copy: the
  ;; delete travels through `bm/remap-changes` with the pair map and must
  ;; name the branch's copy, not main's row — the branch holds neither the
  ;; id the change was computed with nor a second copy of the content
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))
          sobj    (sto/put-object! storage {::sto/content (sto/content "shared-image")
                                            :bucket :file-media-object
                                            :content-type "image/png"})
          fmo     (th/create-file-media-object* {:file-id (:id file)
                                                 :name "shared.png"
                                                 :media-id (:id sobj)})
          _       (apply-change* profile (:id file)
                                 {:type :add-media
                                  :object {:id (:id fmo) :name "shared.png"
                                           :media-id (:id sobj)
                                           :width 100 :height 100
                                           :mtype "image/png"}})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "paired-delete"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          branch-media   (fn []
                           (let [out (th/command! {::th/type :get-file
                                                   ::rpc/profile-id (:id profile)
                                                   :id branch-file-id})]
                             (-> out :result :data :media)))]
      ;; a main-side change so the first update reaches the write path and
      ;; copies main's row into the branch
      (t/is (nil? (:error (apply-change* profile (:id file)
                                         {:type :add-color
                                          :color {:id (uuid/random) :name "C1"
                                                  :color "#112233" :opacity 1}}))))
      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (= :updated (-> out :result :status))))

      (t/testing "the branch holds the media as its own paired copy"
        (let [[media] (vals (branch-media))]
          (t/is (= 1 (count (vals (branch-media)))))
          (t/is (some? media))
          (t/is (not= (:id fmo) (:id media)))
          (t/is (= (:id sobj)
                   (:media-id (th/db-get :file-media-object {:id (:id media)}))))))

      (t/testing "deleting it on main deletes the branch's copy"
        (t/is (nil? (:error (apply-change* profile (:id file)
                                           {:type :del-media :id (:id fmo)}))))
        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (= :updated (-> out :result :status))))
        (t/is (empty? (branch-media)))))))

(t/deftest repeated-updates-squash-no-phantom-media
  ;; a second update from main must not squash media changes the branch
  ;; never made: the write re-points main's media rows at the branch's
  ;; copies, and the squashed log compares the updated branch with main's
  ;; state — if one side is re-pointed and the other is not, the paired
  ;; media reads as deleted+added on every update
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))
          add-media-on-main! (fn [file name]
                               (let [sobj (sto/put-object! storage {::sto/content (sto/content name)
                                                                    :bucket :file-media-object
                                                                    :content-type "image/png"})
                                     fmo  (th/create-file-media-object* {:file-id (:id file)
                                                                         :name name
                                                                         :media-id (:id sobj)})]
                                 (apply-change* profile (:id file)
                                                {:type :add-media
                                                 :object {:id (:id fmo) :name name
                                                          :media-id (:id sobj)
                                                          :width 100 :height 100
                                                          :mtype "image/png"}})))
          add-color-on-main! (fn [file]
                               (apply-change* profile (:id file)
                                              {:type :add-color
                                               :color {:id (uuid/random) :name "C"
                                                       :color "#112233" :opacity 1}}))
          update! (fn [branch-id]
                    (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id}))]

      (t/testing "media added on main after the fork"
        (let [file    (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id})
              create  (:result (th/command! {::th/type :create-file-branch
                                             ::rpc/profile-id (:id profile)
                                             :file-id (:id file)
                                             :name "after-fork"}))
              branch-id (:id create)]
          (t/is (nil? (:error (add-media-on-main! file "after.png"))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          ;; again, with and without new main changes
          (t/is (nil? (:error (add-color-on-main! file))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))))

      (t/testing "media present before the fork"
        (let [file    (th/create-file* 2 {:profile-id (:id profile)
                                          :project-id proj-id})
              _       (t/is (nil? (:error (add-media-on-main! file "before.png"))))
              create  (:result (th/command! {::th/type :create-file-branch
                                             ::rpc/profile-id (:id profile)
                                             :file-id (:id file)
                                             :name "before-fork"}))
              branch-id (:id create)]
          ;; a main-side change so the update reaches the write path
          (t/is (nil? (:error (add-color-on-main! file))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          ;; again, with and without new main changes
          (t/is (nil? (:error (add-color-on-main! file))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id)))))))))

;; --- The branch-merge policy switch ---
;;
;; `resources/app/branch-merge-policies.edn` picks one alternative per
;; policy; the backend re-reads it on mtime change and binds the result
;; around every compare, merge, update and listing computation. These
;; tests pin what a developer flipping that file relies on.

(t/deftest policies-file-invalid-falls-back-to-defaults
  ;; An invalid policies file must not take a merge down with it: it
  ;; logs a warning and the engine runs the built-in defaults.
  (let [path    (doto (java.io.File/createTempFile "branch-policies-" ".edn")
                  (.deleteOnExit))
        logged  (volatile! [])
        run     (fn [content mtime]
                  (spit path content)
                  (.setLastModified path mtime)
                  (vreset! logged [])
                  (with-redefs [cf/config (assoc cf/config
                                                 :branch-merge-policies-file (str path))
                                ;; `l/warn` expands to `emit-log`, so this
                                ;; captures what the loader logs
                                l/emit-log (fn [props _cause _context _logger level _sync?]
                                             (let [props (if (delay? props) @props props)]
                                               (vswap! logged conj
                                                       {:level level
                                                        :props (into {} props)})))]
                    (fbp/effective-policies)))
        warned? (fn []
                  (some (fn [record]
                          (and (= :warn (:level record))
                               (= "invalid branch merge policies file"
                                  (:hint (:props record)))))
                        @logged))]

    ;; a value outside the policy's alternatives is invalid
    (t/is (= bm/default-policies (run "{:same-parent-reorder :not-an-alternative}"
                                      1700000000000)))
    (t/is (warned?))

    ;; a file that is not a policy map at all is invalid the same way
    (t/is (= bm/default-policies (run "[1 2 3]" 1700000001000)))
    (t/is (warned?))))

(t/deftest policies-file-reloads-on-mtime-change
  ;; Editing the policies file changes the effective policy the very
  ;; next compare reads — no restart: the loader re-reads on mtime
  ;; change and binds the result around the comparison.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (th/create-file* 1 {:profile-id (:id profile)
                                        :project-id (:default-project-id profile)
                                        :is-shared false})
          branch-id (-> (th/command! {::th/type :create-file-branch
                                      ::rpc/profile-id (:id profile)
                                      :file-id (:id file)
                                      :name "policies-reload"})
                        :result :id)
          path      (doto (java.io.File/createTempFile "branch-policies-" ".edn")
                      (.deleteOnExit))
          seen      (volatile! nil)
          write!    (fn [content mtime]
                      (spit path content)
                      (.setLastModified path mtime))
          compare!  (fn []
                      (let [out (th/command! {::th/type :get-branch-diff
                                              ::rpc/profile-id (:id profile)
                                              :branch-id branch-id})]
                        (t/is (nil? (:error out)))))]

      (write! "{:same-parent-reorder :refuse}" 1700000000000)
      (with-redefs [cf/config (assoc cf/config
                                     :branch-merge-policies-file (str path))
                    bm/compute-merge (fn [& _]
                                       (vreset! seen bm/*policies*)
                                       {:changes [] :conflicts []})]
        (compare!)
        (t/is (= :refuse (get @seen :same-parent-reorder)))

        ;; the same file, edited in place so its mtime moves, is picked
        ;; up by the next compare: the switch needs no restart
        (write! "{:same-parent-reorder :ignore}" 1700000001000)
        (compare!)
        (t/is (= :ignore (get @seen :same-parent-reorder)))))))

(t/deftest summary-cache-key-changes-with-policies
  ;; Two different policies must never share a cached summary: the key
  ;; hashes the policies the value was computed under.
  (let [row       {:base-snapshot-id (uuid/random) :source-revn 1 :branch-revn 2}
        key-of    (fn [policies]
                    (binding [bm/*policies* policies]
                      (#'fbranch/summary-cache-key row)))
        k-default (key-of bm/default-policies)
        k-ignore  (key-of (assoc bm/default-policies :same-parent-reorder :ignore))
        k-expand  (key-of (assoc bm/default-policies :container-delete-over-main-edits :expand))]

    ;; the key is stable for the same policies …
    (t/is (= k-default (key-of bm/default-policies)))
    ;; … and distinct for different ones
    (t/is (not= k-default k-ignore))
    (t/is (not= k-default k-expand))
    (t/is (not= k-ignore k-expand))))
