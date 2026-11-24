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
   [app.features.file-changes :as fchanges]
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
            ;; the source file card reports the live branches of its family
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
        ;; the source file is not a branch -> nil
        (let [out (th/command! {::th/type :get-file-branch-info
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)})]
          (t/is (nil? (:error out)))
          (t/is (nil? (:result out)))))

      (t/testing "merge base snapshot created on the source"
        (let [rows (th/db-query :file-change {:file-id (:id file)})]
          (t/is (pos? (count rows)))
          (t/is (some #(= "system" (:created-by %)) rows))))

      (t/testing "diff right after creation has no conflicts"
        (let [out   (th/command! {::th/type :get-file-branch-diff
                                  ::rpc/profile-id (:id profile)
                                  :branch-id @branch-meta-id})
              stats (-> out :result :stats)
              meta  (-> out :result :meta)]
          (t/is (nil? (:error out)))
          (t/is (map? stats))
          ;; base == source == branch at fork -> nothing to merge, no conflicts
          (t/is (= 0 (:conflicts stats)))
          ;; the resolution UI gets the "when" of each side
          (t/is (some? (:source-at meta)))
          (t/is (some? (:branch-at meta)))
          (t/is (some? (:base-at meta)))))

      (t/testing "merge branch into the source (clean, no-op)"
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
          source-page-id (uuid/random)
          branch-page-id (uuid/random)]

      (t/testing "diverge both sides: one page on the source, one on the branch"
        (let [mf  (th/db-get :file {:id (:id file)})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)
                                :session-id (uuid/random)
                                :revn (:revn mf)
                                :vern (:vern mf)
                                :features cfeat/supported-features
                                :changes [{:type :add-page :id source-page-id :name "source-side"}]})]
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
        ;; one logical change on the branch (the added color), the source untouched
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

      (t/testing "merge brings the new color into the source"
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

(t/deftest update-branch-pulls-source-changes
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
          color    {:id color-id :name "SourceColor" :color "#00ff00" :opacity 1}]

      (t/testing "add a color on the source (after the branch was created)"
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

      (t/testing "compare in the :source->branch direction shows the source's incoming change"
        (let [diff (:result (th/command! {::th/type :get-file-branch-diff
                                          ::rpc/profile-id (:id profile)
                                          :branch-id branch-id
                                          :direction :source->branch}))
              added (filterv #(= :added (:status %)) (:changes diff))]
          (t/is (= 1 (-> diff :stats :added)))
          (t/is (= color-id (-> added first :id)))))

      (t/testing "update the branch from the source"
        (let [out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (let [out    (th/command! {::th/type :get-file
                                   ::rpc/profile-id (:id profile)
                                   :id branch-file-id})
              colors (-> out :result :data :colors)]
          (t/is (contains? colors color-id)))

        ;; merge base repositioned to the current state of the source
        (let [[row] (th/db-query :file-branch {:id branch-id})
              mf    (th/db-get :file {:id (:id file)})]
          (t/is (= (:revn mf) (:base-source-revn row))))))))

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

      (t/testing "merge brings the new page into the source"
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
  ;; source: a rect at the page root. branch: that rect moved into a new board.
  ;; after merge the source must have the rect INSIDE the board and NOT loose at
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

      (t/testing "add a rect at the root of the source"
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
  ;; create a component on a branch, merge it into the source, and verify the
  ;; merged main instance is still a valid component head (component-id set,
  ;; component-file re-pointed to the source) — regression: it was detached.
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

      (t/testing "merge integrates the component into the source as a real head"
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
          ;; the component registry row landed in the source
          (t/is (contains? (:components data) comp-id))
          ;; the main instance is NOT detached: still a head pointing at the source
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
        (let [out (th/command! {::th/type :update-file-branch-info
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :name "renamed"})]
          (t/is (nil? (:error out))))
        (let [[row] (th/db-query :file-branch {:id branch-id})]
          (t/is (= "renamed" (:name row)))))

      (t/testing "archive hides it from the default list"
        (let [out (th/command! {::th/type :archive-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
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
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :deleted (-> out :result :status))))
        (let [[row]  (th/db-query :file-branch {:id branch-id})
              [frow] (th/db-query :file {:id branch-file-id})]
          (t/is (some? (:deleted-at row)))
          (t/is (some? (:deleted-at frow))))))))

(t/deftest update-resolves-conflict-to-source
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

      ;; color exists on the source before branching (base = red)
      (apply-change (:id file) {:type :add-color :color {:id cid :name "Brand" :color "#ff0000" :opacity 1}})

      (let [create (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "b"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        ;; the source edits the color -> green; branch edits it -> blue (conflict)
        (apply-change (:id file) {:type :mod-color :color {:id cid :name "Brand" :color "#00ff00" :opacity 1}})
        (apply-change branch-file-id {:type :mod-color :color {:id cid :name "Brand" :color "#0000ff" :opacity 1}})

        (t/testing "update without resolutions reports conflicts"
          (let [out (th/command! {::th/type :update-file-branch-from-source
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :conflicts (-> out :result :status)))))

        (t/testing "resolving to the source brings the source's value into the branch"
          (let [out (th/command! {::th/type :update-file-branch-from-source
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :resolutions {cid :source}})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))
          (let [out   (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id profile)
                                    :id branch-file-id})
                color (get-in out [:result :data :colors cid])]
            (t/is (= "#00ff00" (:color color)))))))))

(t/deftest branch-count-counts-from-the-root-without-comparing
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          stranger (th/create-profile* 2 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          create!  (fn [name]
                     (:result (th/command! {::th/type :create-file-branch
                                            ::rpc/profile-id (:id profile)
                                            :file-id (:id file)
                                            :name name})))
          count-of (fn [profile-id file-id]
                     ;; the count answers on every file open: it must never
                     ;; reach the comparison the listing pays for
                     (with-redefs [fbranch/cached-diff-counts
                                   (fn [& _] (throw (ex-info "compared" {})))]
                       (th/command! {::th/type :get-file-branch-count
                                     ::rpc/profile-id profile-id
                                     :file-id file-id})))]

      (t/testing "a file with no branches counts zero"
        (let [out (count-of (:id profile) (:id file))]
          (t/is (nil? (:error out)))
          (t/is (= {:count 0} (:result out)))))

      (let [kept     (create! "kept")
            archived (create! "archived")
            deleted  (create! "deleted")]
        (th/command! {::th/type :archive-file-branch
                      ::rpc/profile-id (:id profile)
                      :branch-id (:id archived)})
        (th/command! {::th/type :delete-file-branch
                      ::rpc/profile-id (:id profile)
                      :branch-id (:id deleted)})

        (t/testing "archived branches count, deleted ones do not"
          (t/is (= {:count 2} (:result (count-of (:id profile) (:id file))))))

        (t/testing "from a branch, the branches of its family root are counted"
          (t/is (= {:count 2} (:result (count-of (:id profile) (:branch-file-id kept))))))

        (t/testing "a profile that cannot read the file is refused"
          (let [out (count-of (:id stranger) (:id file))]
            (t/is (= :not-found (-> out :error ex-data :type))))
          (let [out (count-of (:id stranger) (:branch-file-id kept))]
            (t/is (= :not-found (-> out :error ex-data :type)))))))))

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

(def ^:private file-plugin-change
  {:type :set-plugin-data :object-type :file :namespace :my-plugin :key "foo" :value "bar"})

(def ^:private base-font-size-change
  {:type :set-base-font-size :base-font-size "20px"})

(t/deftest merge-applies-file-level-changes
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
          source-data (fn []
                        (-> (th/command! {::th/type :get-file
                                          ::rpc/profile-id (:id profile)
                                          :id (:id file)})
                            :result :data))]
      (doseq [[label change landed?]
              [["file plugin data" file-plugin-change
                #(= "bar" (get-in % [:plugin-data :my-plugin "foo"]))]
               ["base font size" base-font-size-change
                #(= "20px" (get-in % [:options :base-font-size]))]]]
        (t/testing label
          (let [create (:result (th/command! {::th/type :create-file-branch
                                              ::rpc/profile-id (:id profile)
                                              :file-id (:id file)
                                              :name label}))]
            (t/is (nil? (:error (apply-change* profile (:branch-file-id create) change))))
            (let [info (:result (th/command! {::th/type :get-file-branch-info
                                              ::rpc/profile-id (:id profile)
                                              :file-id (:branch-file-id create)}))]
              (t/is (= [1 0 0] ((juxt :ahead :behind :conflicts) info))))
            (let [out (th/command! {::th/type :merge-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-id (:id create)})]
              (t/is (nil? (:error out)))
              (t/is (= :merged (-> out :result :status))))
            (t/is (landed? (source-data)))))))))

(t/deftest merge-reports-file-level-conflicts
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "b"}))]
      (apply-change* profile (:branch-file-id create) file-plugin-change)
      (apply-change* profile (:branch-file-id create) base-font-size-change)
      (apply-change* profile (:id file) (assoc file-plugin-change :value "source"))
      (apply-change* profile (:id file) (assoc base-font-size-change :base-font-size "18px"))
      (let [info (:result (th/command! {::th/type :get-file-branch-info
                                        ::rpc/profile-id (:id profile)
                                        :file-id (:branch-file-id create)}))]
        (t/is (= 2 (:conflicts info))))
      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id (:id create)})]
        (t/is (= :conflicts (-> out :result :status)))))))

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

(t/deftest merge-records-itself-and-deletes-the-branch
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create! (fn [name]
                    (:result (th/command! {::th/type :create-file-branch
                                           ::rpc/profile-id (:id profile)
                                           :file-id (:id file)
                                           :name name})))
          merge!  (fn [branch-id params]
                    (th/command! (merge {::th/type :merge-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :branch-id branch-id}
                                        params)))
          first-b  (create! "first")
          second-b (create! "second")]

      (let [out (merge! (:id first-b) {:comment "  Ships the new header  "})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (t/testing "the merged branch and its file are deleted"
        (let [[row] (th/db-query :file-branch {:id (:id first-b)})
              bf    (th/db-get :file {:id (:branch-file-id first-b)} {::db/remove-deleted false})]
          (t/is (= "merged" (:status row)))
          (t/is (some? (:deleted-at row)))
          (t/is (some? (:deleted-at bf)))))

      (let [out (merge! (:id second-b) {:comment "   "})]
        (t/is (nil? (:error out))))

      (t/testing "the merges are listed newest first, from the source or a branch"
        (let [out    (th/command! {::th/type :get-file-branch-merges
                                   ::rpc/profile-id (:id profile)
                                   :file-id (:id file)})
              merges (:result out)]
          (t/is (nil? (:error out)))
          (t/is (= ["second" "first"] (mapv :branch-name merges)))
          (t/is (every? #(= (:id profile) (:merged-by %)) merges))
          (t/is (every? #(= (:id file) (:file-id %)) merges))
          (t/is (every? #(= "merge" (:kind %)) merges))
          (t/is (= [nil "Ships the new header"] (mapv :comment merges))
                "the comment is trimmed, and a blank one is no comment")
          (t/is (every? (comp nil? :review-id) merges))))

      (t/testing "a merged branch leaves the branch listing"
        (let [out (th/command! {::th/type :get-file-branches
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)
                                :include-archived true})]
          (t/is (empty? (:result out))))))))

(t/deftest the-merges-of-a-file-need-read-permissions
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          other   (th/create-profile* 2 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
          out     (th/command! {::th/type :get-file-branch-merges
                                ::rpc/profile-id (:id other)
                                :file-id (:id file)})]
      (t/is (th/ex-of-type? (:error out) :not-found)))))

(t/deftest merge-requires-source-edition-permissions
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
      ;; viewer of the source
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

      ;; but profile2 CAN update the branch from the source (edits the branch)
      (let [out (th/command! {::th/type :update-file-branch-from-source
                              ::rpc/profile-id (:id profile2)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))))))

(t/deftest merge-refuses-stale-expected-source-revn
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
                              :expected-source-revn 9999})
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

      ;; color exists on the source before branching (base = red)
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id cid :name "Brand" :color "#ff0000" :opacity 1}})

      (let [create (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "typo"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        ;; the source edits the color -> green; branch edits it -> blue (conflict)
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

        (t/testing "a typo in a per-attr value is refused, not silently read as the source"
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

(t/deftest update-refuses-stale-expected-source-revn
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

      ;; the source advances after the branch was created
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id cid :name "M" :color "#00ff00" :opacity 1}})

      (t/testing "a stale token is refused and the branch is unchanged"
        (let [out   (th/command! {::th/type :update-file-branch-from-source
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :expected-source-revn 9999})
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
              out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :expected-source-revn (:revn mf)})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (contains? (-> out :result :data :colors) cid)))))))

(t/deftest conflict-responses-carry-the-source-revn
  ;; A `:conflicts` response is what a resolving client works from, so it
  ;; names the revision of the source its conflicts were computed against. The
  ;; client sends that revision back as `expected-source-revn` with its
  ;; resolutions, and a source that moved meanwhile is refused.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile     (th/create-profile* 1 {:is-active true})
          proj-id     (:default-project-id profile)
          file        (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id})
          cid         (uuid/random)
          source-revn (fn [] (:revn (th/db-get :file {:id (:id file)})))]

      ;; color exists on the source before branching (base = red)
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id cid :name "Brand" :color "#ff0000" :opacity 1}})

      (let [create (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "conflicting"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        ;; the source edits the color -> green; branch edits it -> blue (conflict).
        ;; The source moves past the revision the branch was cut at, so the
        ;; revision a response names is the current one, not the base's.
        (apply-change* profile (:id file)
                       {:type :mod-color :color {:id cid :name "Brand" :color "#00ff00" :opacity 1}})
        (apply-change* profile branch-file-id
                       {:type :mod-color :color {:id cid :name "Brand" :color "#0000ff" :opacity 1}})

        (t/testing "an update that stops on conflicts names the source's revision"
          (let [revn (source-revn)
                out  (th/command! {::th/type :update-file-branch-from-source
                                   ::rpc/profile-id (:id profile)
                                   :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= revn (-> out :result :source-revn)))))

        (t/testing "a merge that stops on conflicts names the source's revision"
          (let [revn (source-revn)
                out  (th/command! {::th/type :merge-file-branch
                                   ::rpc/profile-id (:id profile)
                                   :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= revn (-> out :result :source-revn)))

            (t/testing "and the merge accepts that revision back with resolutions"
              (let [retry (th/command! {::th/type :merge-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :branch-id branch-id
                                        :resolutions {cid :branch}
                                        :expected-source-revn (-> out :result :source-revn)})]
                (t/is (nil? (:error retry)))
                (t/is (= :merged (-> retry :result :status)))))))))))

(t/deftest revn-gate-survives-update-from-source
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
      ;; ...while the source advances MORE times than the branch (its revn ends up
      ;; far ahead of the branch's own revn counter)
      (doseq [[id nm] [[m1 "M1"] [m2 "M2"] [m3 "M3"]]]
        (apply-change* profile (:id file)
                       {:type :add-color :color {:id id :name nm :color "#00ff00" :opacity 1}}))

      (let [out (th/command! {::th/type :update-file-branch-from-source
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

;;; --- Stacked branches (`:branch-stacking`)

(defn- stacking-flags
  []
  (conj cf/flags :branching :branch-stacking))

(defn- color-change
  [id]
  {:type :add-color
   :color {:id id :name (str "c-" id) :color "#112233" :opacity 1}})

(defn- colors-of
  [profile file-id]
  (-> (th/command! {::th/type :get-file
                    ::rpc/profile-id (:id profile)
                    :id file-id})
      :result :data :colors keys set))

(t/deftest branch-of-a-branch-with-the-stacking-flag
  (with-redefs [cf/flags (stacking-flags)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})
          parent  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "parent"}))
          out     (th/command! {::th/type :create-file-branch
                                ::rpc/profile-id (:id profile)
                                :file-id (:branch-file-id parent)
                                :name "child"})
          child   (:result out)]
      (t/is (nil? (:error out)))
      (t/is (= (:branch-file-id parent)
               (:source-file-id (th/db-get :file-branch {:id (:id child)}))))
      (t/testing "the whole chain is one family"
        (let [family (:family-id (th/db-get :file {:id (:id file)}))]
          (t/is (= family (:family-id (th/db-get :file {:id (:branch-file-id parent)}))))
          (t/is (= family (:family-id (th/db-get :file {:id (:branch-file-id child)})))))))))

(t/deftest child-merges-into-its-parent-branch
  (with-redefs [cf/flags (stacking-flags)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id (:default-project-id profile)})
          parent   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "parent"}))
          child    (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:branch-file-id parent)
                                          :name "child"}))
          color-id (uuid/random)]
      (t/is (nil? (:error (apply-change* profile (:branch-file-id child) (color-change color-id)))))

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id (:id child)})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (t/testing "the parent branch takes the change in its op log, the root does not"
        (t/is (contains? (colors-of profile (:branch-file-id parent)) color-id))
        (t/is (seq (th/db-query :file-branch-change {:branch-id (:id parent)})))
        (t/is (empty? (th/db-query :file-data {:file-id (:branch-file-id parent) :type "main"})))
        (t/is (not (contains? (colors-of profile (:id file)) color-id))))

      (t/testing "the merge is recorded on the parent"
        (let [[row] (th/db-query :file-branch-merge {:file-id (:branch-file-id parent)})]
          (t/is (= "child" (:branch-name row)))))

      (t/testing "merging the parent then brings the change into the root"
        (t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :branch-id (:id parent)}))))
        (t/is (contains? (colors-of profile (:id file)) color-id))))))

(t/deftest merging-a-parent-hands-its-open-child-to-the-target
  (with-redefs [cf/flags (stacking-flags)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (th/create-file* 1 {:profile-id (:id profile)
                                        :project-id (:default-project-id profile)})
          parent    (:result (th/command! {::th/type :create-file-branch
                                           ::rpc/profile-id (:id profile)
                                           :file-id (:id file)
                                           :name "parent"}))
          parent-color (uuid/random)
          _         (apply-change* profile (:branch-file-id parent) (color-change parent-color))
          child     (:result (th/command! {::th/type :create-file-branch
                                           ::rpc/profile-id (:id profile)
                                           :file-id (:branch-file-id parent)
                                           :name "child"}))
          child-color (uuid/random)
          _         (apply-change* profile (:branch-file-id child) (color-change child-color))
          base-id   (:base-snapshot-id (th/db-get :file-branch {:id (:id child)}))]

      (t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :branch-id (:id parent)}))))

      (t/testing "the child now branches from the root, its base with it"
        (let [row (th/db-get :file-branch {:id (:id child)})]
          (t/is (= "open" (:status row)))
          (t/is (= (:id file) (:source-file-id row)))
          (t/is (= 0 (:base-source-revn row)))
          (t/is (= (:id file) (:file-id (th/db-get :file-change {:id base-id}
                                                   {::db/remove-deleted false}))))))

      (t/testing "the child still derives its state and compares against the root"
        (let [colors (colors-of profile (:branch-file-id child))]
          (t/is (contains? colors parent-color))
          (t/is (contains? colors child-color)))
        (let [out (th/command! {::th/type :get-file-branch-diff
                                ::rpc/profile-id (:id profile)
                                :branch-id (:id child)})]
          (t/is (nil? (:error out)))))

      (t/testing "the child merges into the root"
        (t/is (nil? (:error (th/command! {::th/type :merge-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :branch-id (:id child)}))))
        (let [colors (colors-of profile (:id file))]
          (t/is (contains? colors parent-color))
          (t/is (contains? colors child-color)))))))

(defn- branch-chain*
  "A file, a branch of it and a branch of that branch."
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        proj-id (:default-project-id profile)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id proj-id})
        create  (fn [source-id name]
                  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id source-id
                                         :name name})))
        child   (create (:id file) "child")
        grand   (create (:branch-file-id child) "grandchild")]
    {:profile profile :proj-id proj-id :file file :branches [child grand]}))

(defn- archive-branch*
  [profile branch archived]
  (th/command! {::th/type :archive-file-branch
                ::rpc/profile-id (:id profile)
                :branch-id (:id branch)
                :archived archived}))

(t/deftest the-branch-quota-counts-the-whole-family
  ;; Counted by direct source, the chain below holds one branch per
  ;; source, so a third branch from the root passed a quota of two.
  (with-redefs [cf/flags (stacking-flags)
                cf/get   (th/config-get-mock {:quotes-branches-per-file 2})]
    (let [{:keys [profile proj-id file branches]} (branch-chain*)
          [child grand] branches
          create   (fn [source-id name]
                     (th/command! {::th/type :create-file-branch
                                   ::rpc/profile-id (:id profile)
                                   :file-id source-id
                                   :name name}))
          count-of (fn [file-id]
                     (-> (th/command! {::th/type :get-file-branch-count
                                       ::rpc/profile-id (:id profile)
                                       :file-id file-id})
                         :result :count))]

      (t/testing "the chain fills the family's two slots"
        (doseq [source-id [(:id file) (:branch-file-id child) (:branch-file-id grand)]]
          (let [data (-> (create source-id "one-too-many") :error ex-data)]
            (t/is (= :restriction (:type data)))
            (t/is (= :max-quote-reached (:code data))))))

      (t/testing "every file of the family shows the count the quota checks"
        (doseq [file-id [(:id file) (:branch-file-id child) (:branch-file-id grand)]]
          (t/is (= 2 (count-of file-id))))
        (let [rows (:result (th/command! {::th/type :get-project-files
                                          ::rpc/profile-id (:id profile)
                                          :project-id proj-id}))
              src  (first (filter #(= (:id %) (:id file)) rows))]
          (t/is (= 2 (:branches-count src)))))

      (t/testing "an archived branch still counts, a deleted one frees its slot"
        (t/is (nil? (:error (archive-branch* profile grand true))))
        (t/is (= 2 (count-of (:id file))))
        (t/is (nil? (:error (th/command! {::th/type :delete-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :branch-id (:id grand)}))))
        (t/is (= 1 (count-of (:id file))))
        (t/is (nil? (:error (create (:branch-file-id child) "fits"))))))))

(t/deftest merge-into-an-archived-branch-is-refused
  ;; The merge checked only the merged branch: an archived parent took
  ;; its child's changes.
  (with-redefs [cf/flags (stacking-flags)]
    (let [{:keys [profile branches]} (branch-chain*)
          [child grand] branches
          _           (apply-change* profile (:branch-file-id grand) (color-change (uuid/random)))
          merge-grand (fn []
                        (th/command! {::th/type :merge-file-branch
                                      ::rpc/profile-id (:id profile)
                                      :branch-id (:id grand)}))]
      (t/is (nil? (:error (archive-branch* profile child true))))

      (let [data (-> (merge-grand) :error ex-data)]
        (t/is (= :validation (:type data)))
        (t/is (= :source-branch-not-open (:code data))))
      (t/is (= "open" (:status (th/db-get :file-branch {:id (:id grand)}))))

      (t/testing "the merge goes through once the parent is restored"
        (t/is (nil? (:error (archive-branch* profile child false))))
        (let [out (merge-grand)]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))))))

(t/deftest an-archived-branch-is-no-source
  ;; Creation checked only the stacking flag and the update checked only
  ;; the child, so both ran against an archived parent.
  (with-redefs [cf/flags (stacking-flags)]
    (let [{:keys [profile branches]} (branch-chain*)
          [child grand] branches
          _ (apply-change* profile (:branch-file-id child) (color-change (uuid/random)))
          _ (t/is (nil? (:error (archive-branch* profile child true))))]

      (t/testing "no branch is cut from it"
        (let [data (-> (th/command! {::th/type :create-file-branch
                                     ::rpc/profile-id (:id profile)
                                     :file-id (:branch-file-id child)
                                     :name "from-archived"})
                       :error ex-data)]
          (t/is (= :validation (:type data)))
          (t/is (= :source-branch-not-open (:code data)))))

      (t/testing "its child does not update from it"
        (let [data (-> (th/command! {::th/type :update-file-branch-from-source
                                     ::rpc/profile-id (:id profile)
                                     :branch-id (:id grand)})
                       :error ex-data)]
          (t/is (= :validation (:type data)))
          (t/is (= :source-branch-not-open (:code data)))))

      (t/testing "a restored branch is a source again"
        (t/is (nil? (:error (archive-branch* profile child false))))
        (t/is (nil? (:error (th/command! {::th/type :update-file-branch-from-source
                                          ::rpc/profile-id (:id profile)
                                          :branch-id (:id grand)}))))))))

(defn- branch-files-deleted?
  [branches]
  (every? #(some? (:deleted-at (th/db-get :file {:id (:branch-file-id %)}
                                          {::db/remove-deleted false})))
          branches))

(t/deftest deleting-a-project-reaches-a-grandchild
  (with-redefs [cf/flags (stacking-flags)]
    (let [{:keys [proj-id branches]} (branch-chain*)]
      (th/run-task! :delete-object {:object :project :id proj-id :deleted-at (ct/in-future {:days 7})})
      (t/is (branch-files-deleted? branches)))))

(t/deftest deleting-a-root-file-reaches-a-grandchild
  (with-redefs [cf/flags (stacking-flags)]
    (let [{:keys [file branches]} (branch-chain*)]
      (th/run-task! :delete-object {:object :file :id (:id file) :deleted-at (ct/in-future {:days 7})})
      (t/is (branch-files-deleted? branches)))))

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
        ;; the branch file is marked, and the edge of a branch no merge
        ;; accepted is deleted with it
        (t/is (nil? row))
        (t/is (some? (:deleted-at bf)))))))

(t/deftest merge-copies-branch-media-into-source
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

          ;; media present on the SOURCE before branching (will be paired)
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

      (t/testing "merging brings the new media with a source-owned row"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [out   (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id (:id file)})
              media (-> out :result :data :media)
              added (->> (vals media) (filter #(= "new.png" (:name %))) first)]
          (t/is (some? added))
          ;; the merged entry references a row owned by the SOURCE (a copy), not
          ;; the branch's row (which dies with the branch file)
          (t/is (not= (:id fmo2) (:id added)))
          (let [row (th/db-get :file-media-object {:id (:id added)})]
            (t/is (= (:id file) (:file-id row)))
            (t/is (= (:id sobj2) (:media-id row)))))))))

(t/deftest merge-copies-colour-image-media-into-source
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
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [out    (th/command! {::th/type :get-file
                                 ::rpc/profile-id (:id profile)
                                 :id (:id file)})
            img-id (get-in out [:result :data :colors cid :image :id])]
        (t/is (some? img-id))
        ;; the colour's image is a row owned by the SOURCE (a copy), not the
        ;; branch's row (which dies with the branch file)
        (t/is (not= (:id fmo) img-id))
        (let [row (th/db-get :file-media-object {:id img-id})]
          (t/is (= (:id file) (:file-id row)))
          (t/is (= (:id sobj) (:media-id row))))))))

(t/deftest update-from-source-copies-colour-image-media-into-branch
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

          ;; media ADDED on the SOURCE after forking, used only by a library colour
          sobj   (sto/put-object! storage {::sto/content (sto/content "source-colour-image")
                                           :bucket :file-media-object
                                           :content-type "image/png"})
          fmo    (th/create-file-media-object* {:file-id (:id file)
                                                :name "source-colour.png"
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

      (let [out (th/command! {::th/type :update-file-branch-from-source
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
        ;; in the branch's id space and the op log replays over it)
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
        ;; other ref (`bm/remap-changes`); left in the source's id space it makes
        ;; the colour differ from the new base and lands in the op log
        (t/is (empty? (th/db-query :file-branch-change {:branch-id branch-id})))))))

(t/deftest branch-permissions-come-from-the-team-role
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [owner   (th/create-profile* 1 {:is-active true})
          editor1 (th/create-profile* 2 {:is-active true})
          editor2 (th/create-profile* 3 {:is-active true})
          viewer  (th/create-profile* 4 {:is-active true})
          team-id (:default-team-id owner)
          file    (th/create-file* 1 {:profile-id (:id owner)
                                      :project-id (:default-project-id owner)})
          _       (doseq [[profile role] [[editor1 :editor] [editor2 :editor] [viewer :viewer]]]
                    (th/create-team-role* {:team-id team-id
                                           :profile-id (:id profile)
                                           :role role}))
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id editor1)
                                         :file-id (:id file)
                                         :name "team-roles"}))
          branch-file-id (:branch-file-id create)
          perms-of       (fn [profile]
                           (db/run! th/*system*
                                    #(bfc/get-file-permissions % (:id profile) branch-file-id)))]

      (t/testing "creating a branch grants no file role"
        (t/is (empty? (th/db-query :file-profile-rel {:file-id branch-file-id}))))

      (t/testing "the creator keeps the rights of a team editor"
        (let [perms (perms-of editor1)]
          (t/is (true? (:can-edit perms)))
          (t/is (false? (:is-admin perms)))
          (t/is (false? (:is-owner perms)))))

      (t/testing "another team editor can edit the branch"
        (let [bf  (th/db-get :file {:id branch-file-id})
              out (th/command! {::th/type :update-file
                                ::rpc/profile-id (:id editor2)
                                :id branch-file-id
                                :session-id (uuid/random)
                                :revn (:revn bf)
                                :vern (:vern bf)
                                :features cfeat/supported-features
                                :changes [{:type :add-page :id (uuid/random) :name "by-editor2"}]})]
          (t/is (nil? (:error out)))))

      (t/testing "a team viewer can read the branch but not edit it"
        (let [perms (perms-of viewer)]
          (t/is (true? (:can-read perms)))
          (t/is (false? (:can-edit perms))))))))

(t/deftest branches-move-with-their-source-file
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [owner   (th/create-profile* 1 {:is-active true})
          editor  (th/create-profile* 2 {:is-active true})
          team    (th/create-team* 1 {:profile-id (:id owner)})
          project (th/create-project* 1 {:team-id (:id team)
                                         :profile-id (:id owner)})
          file    (th/create-file* 1 {:profile-id (:id owner)
                                      :project-id (:default-project-id owner)})
          _       (th/create-team-role* {:team-id (:id team)
                                         :profile-id (:id editor)
                                         :role :editor})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id owner)
                                         :file-id (:id file)
                                         :name "travels"}))
          branch-file-id (:branch-file-id create)]

      (t/testing "a branch cannot be moved on its own"
        (let [out (th/command! {::th/type :move-files
                                ::rpc/profile-id (:id owner)
                                :project-id (:id project)
                                :ids #{branch-file-id}})]
          (t/is (th/ex-of-code? (:error out) :cant-move-a-branch))))

      (t/testing "moving the source file takes its branches along"
        (let [out (th/command! {::th/type :move-files
                                ::rpc/profile-id (:id owner)
                                :project-id (:id project)
                                :ids #{(:id file)}})]
          (t/is (nil? (:error out))))
        (t/is (= (:id project) (:project-id (th/db-get :file {:id branch-file-id})))))

      (t/testing "an editor of the new team can open the branch"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id editor)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (true? (-> out :result :permissions :can-edit))))))))

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
          (let [out (th/command! {::th/type :detach-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :detached (-> out :result :status)))
            (t/is (true? (-> out :result :changed))))

          (let [row (th/db-get :file {:id branch-file-id})]
            (t/is (false? (:is-branch row))))

          ;; as an ordinary file it gets the owner role of its maker
          (let [rel (th/db-get :file-profile-rel {:file-id branch-file-id
                                                  :profile-id (:id profile)})]
            (t/is (true? (:is-owner rel))))

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
          (let [out (th/command! {::th/type :detach-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :detached (-> out :result :status)))
            (t/is (false? (-> out :result :changed)))))))))

(defn- with-pruning-checked
  "Run `f` with the diff engine wrapped so that every pruned comparison is
  also computed whole-file and the two summaries must agree. Returns the
  number of pruned comparisons the run performed."
  [f]
  (let [orig   bm/compute-merge
        pruned (atom 0)]
    (with-redefs [bm/compute-merge
                  (fn [base source branch dir & [opts]]
                    (let [res (orig base source branch dir opts)]
                      (when (:only-pages opts)
                        (swap! pruned inc)
                        (t/is (= (orig base source branch dir (dissoc opts :only-pages)) res)
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

      (t/testing "a page-scoped edit on the branch and a page on the source"
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
                                :changes [{:type :add-page :id (uuid/random) :name "source-side"}]})]
          (t/is (nil? (:error out)))))

      (t/testing "the compare is pruned and reports the same summary"
        (let [result (volatile! nil)
              pruned (with-pruning-checked
                       (fn []
                         (vreset! result (th/command! {::th/type :get-file-branch-diff
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
                         (vreset! result (th/command! {::th/type :get-file-branch-diff
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
            (t/is (str/includes? (:hint data) "detach"))
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
            (let [out  (th/command! {::th/type :get-file-branch-diff
                                     ::rpc/profile-id (:id profile)
                                     :branch-id branch-id})
                  data (ex-data (:error out))]
              (t/is (some? (:error out)))
              (t/is (= :restriction (:type data)))
              (t/is (= :branching-oplog-limit-exceeded (:code data)))
              (t/is (= 0 (:limit data)))
              (t/is (= 1 (:actual data)))
              (t/is (str/includes? (:hint data) "detach")))))))))

(t/deftest oplog-depth-gate-refuses-a-compare-before-the-derive
  ;; The derive replays the whole op log, so it is the cost the depth gate
  ;; bounds. A compare over a log beyond the limit must refuse before it
  ;; pays for one, and its hint must not offer a merge, which the same
  ;; gate refuses.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          create   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "deep-log"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          derive   @#'bfc/branch-file-data
          derives  (atom 0)
          compare! (fn []
                     (with-redefs [bfc/branch-file-data (fn [& args]
                                                          (swap! derives inc)
                                                          (apply derive args))]
                       (th/command! {::th/type :get-file-branch-diff
                                     ::rpc/profile-id (:id profile)
                                     :branch-id branch-id})))]

      (apply-change* profile branch-file-id
                     {:type :add-page :id (uuid/random) :name "one"})

      (t/testing "under the limit the compare derives the branch"
        (let [out (compare!)]
          (t/is (nil? (:error out)))
          (t/is (pos? @derives) "the probe saw no derive, so its zero below proves nothing")))

      (t/testing "over the limit the compare refuses before the derive"
        (reset! derives 0)
        (with-redefs [cf/get (th/config-get-mock {:branching-max-oplog-changes 0})]
          (let [out  (compare!)
                data (ex-data (:error out))]
            (t/is (= :restriction (:type data)))
            (t/is (= :branching-oplog-limit-exceeded (:code data)))
            (t/is (= :compare (:operation data)))
            (t/is (= 1 (:actual data)))
            (t/is (zero? @derives) "the refused compare derived the branch first")
            (t/is (str/includes? (:hint data) "detach"))
            (t/is (not (str/includes? (:hint data) "merge")))))))))

(t/deftest oplog-depth-gate-counts-the-changes-a-row-holds
  ;; The gate bounds a log by the changes it holds, and every op log row stamps
  ;; that number at write time, so the gate reads it rather than decoding a log
  ;; the derive in the same command decodes again. One save carrying two
  ;; changes is one row of depth two, which a row count cannot tell from one.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          create   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)
                                          :name "deep-log"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          derive!        @#'bfc/branch-file-data
          cfg            {::db/conn th/*pool*}
          depth          #(bfc/get-branch-oplog-depth cfg %)
          decoded-depth  #(count (mapcat identity (bfc/get-branch-changes cfg %)))
          rows           #(th/db-query :file-branch-change {:file-id branch-file-id})
          reads          (atom 0)
          derives        (atom 0)
          compare!       (fn []
                           (with-redefs [bfc/get-branch-changes
                                         (fn [& args]
                                           (swap! reads inc)
                                           (apply bfc/get-branch-changes args))
                                         bfc/branch-file-data
                                         (fn [& args]
                                           (swap! derives inc)
                                           (apply derive! args))]
                             (th/command! {::th/type :get-file-branch-diff
                                           ::rpc/profile-id (:id profile)
                                           :branch-id branch-id})))]

      (let [f (th/db-get :file {:id branch-file-id})]
        (th/command! {::th/type :update-file
                      ::rpc/profile-id (:id profile)
                      :id branch-file-id
                      :session-id (uuid/random)
                      :revn (:revn f)
                      :vern (:vern f)
                      :features cfeat/supported-features
                      :changes [{:type :add-page :id (uuid/random) :name "one"}
                                {:type :add-page :id (uuid/random) :name "two"}]}))

      (t/testing "a save stamps the changes its row holds"
        (t/is (= 1 (count (rows))))
        (t/is (= 2 (count (mapcat identity (bfc/get-branch-changes cfg branch-file-id))))))

      (t/testing "the stamped depth is the depth of the decoded log"
        (t/is (= (decoded-depth branch-file-id) (depth branch-file-id))))

      (t/testing "the gate refuses on the changes the row holds"
        (with-redefs [cf/get (th/config-get-mock {:branching-max-oplog-changes 1})]
          (let [out  (compare!)
                data (ex-data (:error out))]
            (t/is (= :restriction (:type data)))
            (t/is (= :branching-oplog-limit-exceeded (:code data)))
            (t/is (= 2 (:actual data)) "the gate counted rows instead of the depth")
            (t/is (zero? @reads) "the refused compare decoded the op log")
            (t/is (zero? @derives) "the refused compare derived the branch first"))))

      (t/testing "an update from the source stamps the squash it writes"
        (apply-change* profile (:id file) {:type :add-page :id (uuid/random) :name "on-source"})
        (let [out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= 1 (count (rows))) "the squash is one row")
          (t/is (= (decoded-depth branch-file-id) (depth branch-file-id)))
          (t/is (= (depth branch-file-id) (:changes-count (first (rows))))))))))

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
              out     (th/command! {::th/type :detach-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-id (-> created :result :id)})
              props   (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "detach" (:branch-operation props)))
          (t/is (= "detached" (:branch-outcome props)))
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
                                  :branch-id branch-id})]
            (t/is (= :branch-not-found (-> out :error ex-data :code)))
            (t/is (= :merged (-> @merged :result :status)))
            ;; the merge's deletion is the only one: the row still says
            ;; the branch was merged
            (let [[row] (th/db-query :file-branch {:id branch-id})]
              (t/is (= "merged" (:status row)))
              (t/is (some? (:deleted-at row))))))))))

(t/deftest a-merged-branch-is-gone-for-delete
  ;; a merge deletes its branch: deleting it again finds nothing and does
  ;; not rewrite the status that says the branch was merged
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "merged"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          cid (uuid/random)]
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id cid :name "C" :color "#112233" :opacity 1}})
      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (= :merged (-> out :result :status))))
      (let [out (th/command! {::th/type :delete-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (= :branch-not-found (-> out :error ex-data :code))))
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

      (let [out (th/command! {::th/type :update-file-branch-info
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
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
        (let [out   (th/command! {::th/type :update-file-branch-info
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :name "audited-rename"})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "rename-branch" (:branch-operation props)))
          (t/is (= "ok" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))))

      (t/testing "archiving reports archive-branch, restoring restore-branch"
        (let [out   (th/command! {::th/type :archive-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "archive-branch" (:branch-operation props)))
          (t/is (= "archived" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props))))
        (let [out   (th/command! {::th/type :archive-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :archived false})
              props (-> out :result meta :app.loggers.audit/props)]
          (t/is (nil? (:error out)))
          (t/is (= "restore-branch" (:branch-operation props)))
          (t/is (= "open" (:branch-outcome props)))
          (t/is (int? (:branch-duration-ms props)))))

      (t/testing "a delete reports delete-branch and its outcome"
        (let [out   (th/command! {::th/type :delete-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})
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

      ;; the source moves after the fork...
      (apply-change* profile (:id file)
                     {:type :add-color
                      :color {:id (uuid/random) :name "M" :color "#00ff00" :opacity 1}})

      ;; ...and the update repositions the merge base to a new snapshot
      (let [out (th/command! {::th/type :update-file-branch-from-source
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (let [brow (first (th/db-query :file-branch {:id branch-id}))
            base (first (th/db-query :file-change {:id (:base-snapshot-id brow)}))
            meta (:meta (:result (th/command! {::th/type :get-file-branch-diff
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
          source-color   (uuid/random)

          ;; validation reports an error the repair cannot fix: the
          ;; repair produces no changes and the re-validation still
          ;; reports the same error
          unfixable (fn [file _libs] (synthetic-validation-error file))
          no-repair (fn [_file _libs _errors] [])]

      (t/testing "the merge refuses and leaves the source and the branch untouched"
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
                        :color {:id source-color :name "M" :color "#445566" :opacity 1}})

        (let [[base] (th/db-query :file-branch {:id branch-id})]
          (with-redefs [cfv/validate-file unfixable
                        cfr/repair-file no-repair]
            (let [out  (th/command! {::th/type :update-file-branch-from-source
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
            (t/is (not (contains? colors source-color))))

          (let [[row] (th/db-query :file-branch {:id branch-id})]
            (t/is (= "open" (:status row)))
            (t/is (= (:base-source-revn base) (:base-source-revn row))))))

      (t/testing "materialize never refuses: the exit door lets the user out"
        (with-redefs [cfv/validate-file unfixable
                      cfr/repair-file no-repair]
          (let [out (th/command! {::th/type :detach-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :detached (-> out :result :status)))
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
          (let [out (th/command! {::th/type :update-file-branch-from-source
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

      (t/testing "the merge drops the cached summary of the source"
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id (uuid/random) :name "B" :color "#112233" :opacity 1}})

        ;; the cache now describes the pre-merge source
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

        (let [out (th/command! {::th/type :update-file-branch-from-source
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
          (let [out (th/command! {::th/type :detach-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id b2-id})]
            (t/is (nil? (:error out)))
            (t/is (= :detached (-> out :result :status)))))

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
;; `:update-file-branch-from-source/global` at one permit each, and would
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
  ;; trusting its stale "open" row: merging the branch a second time,
  ;; recording a second merge and deleting it again.
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
                        ;; the outer one takes any lock
                        (when (compare-and-set! raced? false true)
                          (reset! merged (th/command! {::th/type :merge-file-branch
                                                       ::rpc/profile-id (:id profile)
                                                       :branch-id branch-id})))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :branch-not-found (-> out :error ex-data :code)))))
        (t/is (= :merged (-> @merged :result :status)))
        (t/testing "the source holds the first merge's revn only"
          (t/is (= (-> @merged :result :revn)
                   (:revn (first (th/db-query :file {:id (:id file)}))))))
        (t/testing "the winning merge is the only one recorded"
          (t/is (= 1 (count (th/db-query :file-branch-merge {:file-branch-id branch-id})))))
        (t/testing "the branch is merged and deleted once"
          (let [[row] (th/db-query :file-branch {:id branch-id})]
            (t/is (= "merged" (:status row)))
            (t/is (some? (:deleted-at row)))))))))

(t/deftest merge-releases-the-base-the-lock-shows
  ;; the merge releases the base snapshot pin (on the delete path), and
  ;; the id must come from the row as it stands under the lock: an
  ;; update-from-source that repositions the base while the merge waits
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
      ;; the branch carries its own change and the source moves ahead, so the
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
                        ;; from the source repositions the merge base and
                        ;; releases the pin the branch carried so far
                        (when (compare-and-set! raced? false true)
                          (t/is (nil? (:error (th/command! {::th/type :update-file-branch-from-source
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
  ;; overlapping updates from the source must release the base the first one
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
                          (t/is (nil? (:error (th/command! {::th/type :update-file-branch-from-source
                                                            ::rpc/profile-id (:id profile)
                                                            :branch-id branch-id}))))
                          (let [brow (first (th/db-query :file-branch {:id branch-id}))]
                            (reset! first-base (:base-snapshot-id brow))
                            (reset! superseded
                                    (:deleted-at (first (th/db-query :file-change {:id old-base}))))))
                        (orig-lock conn id))]
          (let [out (th/command! {::th/type :update-file-branch-from-source
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
                                :branch-id branch-id})]
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
                                        :source-revn (:revn (first (th/db-query :file {:id (:id file)})))}))]
        ;; a refused merge publishes nothing
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :expected-source-revn 9999})]
          (t/is (= :file-modified (-> out :error ex-data :code)))
          (t/is (empty? @seen)))
        ;; the merge deletes the branch: its file's clients hear of the
        ;; deletion and the source's of the merge
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (= :merged (-> out :result :status))))
        (let [by-type (into {} (map (juxt (comp :type :message) identity)) @seen)
              {:keys [message source-revn]} (get by-type :file-merged)]
          (t/is (= 2 (count @seen)))
          (t/is (= #{:file-deleted :file-merged} (set (keys by-type))))
          (t/is (= branch-file-id (get-in by-type [:file-deleted :message :file-id])))
          ;; the merge was already committed when the message left
          (t/is (= (:revn message) source-revn)))))))

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
        (let [out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (= :updated (-> out :result :status))))
        (let [[{:keys [topic message branch-revn]}] @seen]
          (t/is (= 1 (count @seen)))
          (t/is (= :file-merged (:type message)))
          (t/is (= branch-file-id topic))
          ;; the update was already committed when the message left
          (t/is (= (:revn message) branch-revn)))))))

(t/deftest detach-takes-the-branch-row-id
  ;; `:branch-id` names the `file_branch` row, as in every other branch
  ;; command; the branch FILE id under the old `:branch-file-id` name is
  ;; refused by the schema.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile        (th/create-profile* 1 {:is-active true})
          proj-id        (:default-project-id profile)
          file           (th/create-file* 1 {:profile-id (:id profile)
                                             :project-id proj-id
                                             :is-shared false})
          create         (:result (th/command! {::th/type :create-file-branch
                                                ::rpc/profile-id (:id profile)
                                                :file-id (:id file)
                                                :name "to-detach"}))
          branch-file-id (:branch-file-id create)]

      (t/testing "the old :branch-file-id param is refused by the schema"
        (let [{:keys [error]} (th/command! {::th/type :detach-file-branch
                                            ::rpc/profile-id (:id profile)
                                            :branch-file-id branch-file-id})]
          (t/is (some? error))
          (t/is (th/ex-of-type? error :validation))
          (t/is (th/ex-of-code? error :params-validation)))
        ;; refused before the handler runs, so the branch is untouched
        (t/is (true? (:is-branch (th/db-get :file {:id branch-file-id})))))

      (t/testing "detach runs on :branch-id"
        (let [{:keys [error result]} (th/command! {::th/type :detach-file-branch
                                                   ::rpc/profile-id (:id profile)
                                                   :branch-id (:id create)})]
          (t/is (nil? error))
          (t/is (= :detached (:status result)))
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
                      (fn [cfg source-data branch]
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
                        (orig-compute cfg source-data branch))]
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
  the squash an update-from-source performs."
  [branch-id]
  (into []
        (mapcat (fn [row] (map :type (blob/decode (:changes row)))))
        (th/db-query :file-branch-change {:branch-id branch-id})))

(def ^:private media-change-types
  #{:add-media :mod-media :del-media})

(t/deftest update-from-source-deletes-the-paired-media-copy
  ;; the source deletes a media object the branch holds as a paired copy: the
  ;; delete travels through `bm/remap-changes` with the pair map and must
  ;; name the branch's copy, not the source's row — the branch holds neither the
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
      ;; a source-side change so the first update reaches the write path and
      ;; copies the source's row into the branch
      (t/is (nil? (:error (apply-change* profile (:id file)
                                         {:type :add-color
                                          :color {:id (uuid/random) :name "C1"
                                                  :color "#112233" :opacity 1}}))))
      (let [out (th/command! {::th/type :update-file-branch-from-source
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

      (t/testing "deleting it on the source deletes the branch's copy"
        (t/is (nil? (:error (apply-change* profile (:id file)
                                           {:type :del-media :id (:id fmo)}))))
        (let [out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (= :updated (-> out :result :status))))
        (t/is (empty? (branch-media)))))))

(t/deftest repeated-updates-squash-no-phantom-media
  ;; a second update from the source must not squash media changes the branch
  ;; never made: the write re-points the source's media rows at the branch's
  ;; copies, and the squashed log compares the updated branch with the source's
  ;; state — if one side is re-pointed and the other is not, the paired
  ;; media reads as deleted+added on every update
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))
          add-media-on-source! (fn [file name]
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
          add-color-on-source! (fn [file]
                                 (apply-change* profile (:id file)
                                                {:type :add-color
                                                 :color {:id (uuid/random) :name "C"
                                                         :color "#112233" :opacity 1}}))
          update! (fn [branch-id]
                    (th/command! {::th/type :update-file-branch-from-source
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id}))]

      (t/testing "media added on the source after the fork"
        (let [file    (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id})
              create  (:result (th/command! {::th/type :create-file-branch
                                             ::rpc/profile-id (:id profile)
                                             :file-id (:id file)
                                             :name "after-fork"}))
              branch-id (:id create)]
          (t/is (nil? (:error (add-media-on-source! file "after.png"))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          ;; again, with and without new source changes
          (t/is (nil? (:error (add-color-on-source! file))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))))

      (t/testing "media present before the fork"
        (let [file    (th/create-file* 2 {:profile-id (:id profile)
                                          :project-id proj-id})
              _       (t/is (nil? (:error (add-media-on-source! file "before.png"))))
              create  (:result (th/command! {::th/type :create-file-branch
                                             ::rpc/profile-id (:id profile)
                                             :file-id (:id file)
                                             :name "before-fork"}))
              branch-id (:id create)]
          ;; a source-side change so the update reaches the write path
          (t/is (nil? (:error (add-color-on-source! file))))
          (t/is (= :updated (-> (update! branch-id) :result :status)))
          (t/is (empty? (filter media-change-types (squashed-log-types branch-id))))
          ;; again, with and without new source changes
          (t/is (nil? (:error (add-color-on-source! file))))
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
                      (let [out (th/command! {::th/type :get-file-branch-diff
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
        k-expand  (key-of (assoc bm/default-policies :container-delete-over-target-edits :expand))]

    ;; the key is stable for the same policies …
    (t/is (= k-default (key-of bm/default-policies)))
    ;; … and distinct for different ones
    (t/is (not= k-default k-ignore))
    (t/is (not= k-default k-expand))
    (t/is (not= k-ignore k-expand))))

;;; --- Selective merge: the exclusion set on the wire ---

(t/deftest merge-with-exclusion-applies-the-rest
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "selective"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          keep-id  (uuid/random)
          drop-id  (uuid/random)]

      (t/testing "the branch adds two colors"
        (apply-change* profile branch-file-id
                       {:type :add-color :color {:id keep-id :name "Keep" :color "#00ff00" :opacity 1}})
        (apply-change* profile branch-file-id
                       {:type :add-color :color {:id drop-id :name "Drop" :color "#0000ff" :opacity 1}}))

      (t/testing "the merge takes the selected change and leaves the excluded one out"
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :excluded #{(bm/change-key {:kind :color :id drop-id})}})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (let [out    (th/command! {::th/type :get-file
                                   ::rpc/profile-id (:id profile)
                                   :id (:id file)})
              colors (-> out :result :data :colors)]
          (t/is (contains? colors keep-id))
          (t/is (not (contains? colors drop-id))))))))

(t/deftest merge-refuses-when-every-change-is-excluded
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "nothing-selected"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          color-id (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id color-id :name "Solo" :color "#ff0000" :opacity 1}})

      (t/testing "a merge whose every change is excluded refuses with :nothing-to-merge"
        (let [out   (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :excluded #{(bm/change-key {:kind :color :id color-id})}})
              error (:error out)]
          (t/is (some? error))
          (t/is (= :nothing-to-merge (-> error ex-data :code))))

        ;; nothing was integrated and the branch survives the refusal
        (t/is (= "open" (:status (first (th/db-query :file-branch {:id branch-id})))))
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)})]
          (t/is (not (contains? (-> out :result :data :colors) color-id)))))

      (t/testing "the update refuses the same way when every incoming change is excluded"
        (let [source-color-id (uuid/random)]
          (apply-change* profile (:id file)
                         {:type :add-color :color {:id source-color-id :name "Source" :color "#00ff00" :opacity 1}})
          (let [out   (th/command! {::th/type :update-file-branch-from-source
                                    ::rpc/profile-id (:id profile)
                                    :branch-id branch-id
                                    :excluded #{(bm/change-key {:kind :color :id source-color-id})}})
                error (:error out)]
            (t/is (some? error))
            (t/is (= :nothing-to-merge (-> error ex-data :code))))
          (let [out (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id})]
            (t/is (not (contains? (-> out :result :data :colors) source-color-id)))))))))

(t/deftest update-with-exclusion-replays-the-branch-from-its-base
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id})
          create  (:result (th/command! {::th/type :create-file-branch
                                         ::rpc/profile-id (:id profile)
                                         :file-id (:id file)
                                         :name "partial-update"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          source-1 (uuid/random)
          source-2 (uuid/random)
          own      (uuid/random)]

      ;; the source advances twice; the branch carries work of its own
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id source-1 :name "M1" :color "#111111" :opacity 1}})
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id source-2 :name "M2" :color "#222222" :opacity 1}})
      (apply-change* profile branch-file-id
                     {:type :add-color :color {:id own :name "Own" :color "#333333" :opacity 1}})

      (t/testing "the update takes the source's selected change and keeps the excluded one out"
        (let [out (th/command! {::th/type :update-file-branch-from-source
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :excluded #{(bm/change-key {:kind :color :id source-1})}})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        ;; get-file derives the branch by replaying the squashed net over
        ;; the repositioned base: this state IS that replay
        (let [out    (th/command! {::th/type :get-file
                                   ::rpc/profile-id (:id profile)
                                   :id branch-file-id})
              colors (-> out :result :data :colors)]
          (t/is (contains? colors source-2))
          (t/is (contains? colors own))
          (t/is (not (contains? colors source-1))))

        ;; the base moved to the source, and the source still holds the excluded work
        (let [[row] (th/db-query :file-branch {:id branch-id})
              mf    (th/db-get :file {:id (:id file)})]
          (t/is (= (:revn mf) (:base-source-revn row))))
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id (:id file)})]
          (t/is (contains? (-> out :result :data :colors) source-1))))

      (t/testing "the excluded source work is a branch-side delta over the new base"
        ;; the branch does not carry it and the base does, so the squashed
        ;; net records the difference as a change of the branch's own
        (let [diff (:result (th/command! {::th/type :get-file-branch-diff
                                          ::rpc/profile-id (:id profile)
                                          :branch-id branch-id}))]
          (t/is (some #(and (= :color (:kind %))
                            (= source-1 (:id %))
                            (= :deleted (:status %)))
                      (:changes diff))))))))

;;; --- The changes a profile marked as viewed ---

(t/deftest review-marks-belong-to-a-profile-a-direction-and-a-change
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [owner    (th/create-profile* 1 {:is-active true})
          mate     (th/create-profile* 2 {:is-active true})
          stranger (th/create-profile* 3 {:is-active true})
          file     (th/create-file* 1 {:profile-id (:id owner)
                                       :project-id (:default-project-id owner)
                                       :is-shared false})
          _        (th/create-team-role* {:team-id (:default-team-id owner)
                                          :profile-id (:id mate)
                                          :role :editor})
          branch   (:result (th/command! {::th/type :create-file-branch
                                          ::rpc/profile-id (:id owner)
                                          :file-id (:id file)
                                          :name "review-marks"}))
          mark     (fn [profile direction change-key fingerprint viewed]
                     (th/command! {::th/type :set-branch-change-mark
                                   ::rpc/profile-id (:id profile)
                                   :branch-id (:id branch)
                                   :direction direction
                                   :change-key change-key
                                   :fingerprint fingerprint
                                   :viewed viewed}))
          marks    (fn [profile direction]
                     (let [out (th/command! {::th/type :get-branch-change-marks
                                             ::rpc/profile-id (:id profile)
                                             :branch-id (:id branch)
                                             :direction direction})]
                       (t/is (nil? (:error out)))
                       (into {} (map (juxt :change-key :fingerprint)) (:result out))))]

      (t/testing "a mark is read back with its fingerprint"
        (t/is (nil? (:error (mark owner :branch->source "shape/p/1" "a1" true))))
        (t/is (nil? (:error (mark owner :branch->source "shape/p/2" "b1" true))))
        (t/is (= {"shape/p/1" "a1" "shape/p/2" "b1"} (marks owner :branch->source))))

      (t/testing "marking again keeps one mark, with the newest fingerprint"
        (t/is (nil? (:error (mark owner :branch->source "shape/p/1" "a2" true))))
        (t/is (= {"shape/p/1" "a2" "shape/p/2" "b1"} (marks owner :branch->source))))

      (t/testing "each direction has its own marks"
        (t/is (= {} (marks owner :source->branch)))
        (t/is (nil? (:error (mark owner :source->branch "shape/p/1" "m1" true))))
        (t/is (= {"shape/p/1" "m1"} (marks owner :source->branch)))
        (t/is (= "a2" (get (marks owner :branch->source) "shape/p/1"))))

      (t/testing "the marks of a profile are not anybody else's"
        (t/is (= {} (marks mate :branch->source)))
        (t/is (nil? (:error (mark mate :branch->source "shape/p/2" "theirs" true))))
        (t/is (= {"shape/p/2" "theirs"} (marks mate :branch->source)))
        (t/is (= "b1" (get (marks owner :branch->source) "shape/p/2"))))

      (t/testing "clearing a mark removes that one alone"
        (t/is (nil? (:error (mark owner :branch->source "shape/p/1" "a2" false))))
        (t/is (= {"shape/p/2" "b1"} (marks owner :branch->source)))
        (t/is (= {"shape/p/1" "m1"} (marks owner :source->branch)))
        (t/is (= {"shape/p/2" "theirs"} (marks mate :branch->source))))

      (t/testing "a profile that cannot read the file neither marks nor reads"
        (let [out (mark stranger :branch->source "shape/p/1" "x" true)]
          (t/is (th/ex-info? (:error out)))
          (t/is (= :not-found (th/ex-type (:error out)))))
        (let [out (th/command! {::th/type :get-branch-change-marks
                                ::rpc/profile-id (:id stranger)
                                :branch-id (:id branch)
                                :direction :branch->source})]
          (t/is (= :not-found (th/ex-type (:error out)))))
        (t/is (= {"shape/p/2" "b1"} (marks owner :branch->source))
              "the refused mark was not written"))

      (t/testing "a branch that does not exist is not found"
        (let [out (th/command! {::th/type :get-branch-change-marks
                                ::rpc/profile-id (:id owner)
                                :branch-id (uuid/next)
                                :direction :branch->source})]
          (t/is (= :not-found (th/ex-type (:error out))))
          (t/is (= :branch-not-found (th/ex-code (:error out)))))))))

(t/deftest merge-and-update-from-source-record-revision-rows
  ;; the revision rows the merge and the update from the source write carry
  ;; their kind, as the save path's do: a kind-filtered read sees them
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile        (th/create-profile* 1 {:is-active true})
          file           (th/create-file* 1 {:profile-id (:id profile)
                                             :project-id (:default-project-id profile)})
          create         (:result (th/command! {::th/type :create-file-branch
                                                ::rpc/profile-id (:id profile)
                                                :file-id (:id file)
                                                :name "kinds"}))
          branch-id      (:id create)
          branch-file-id (:branch-file-id create)
          kinds-of       (fn [file-id session-id]
                           (mapv :kind (th/db-exec! ["SELECT kind FROM file_change
                                                       WHERE file_id = ? AND session_id = ?"
                                                     file-id session-id])))]
      (apply-change* profile (:id file)
                     {:type :add-color :color {:id (uuid/random) :name "Source" :color "#00ff00" :opacity 1}})

      (t/testing "the update from the source"
        (let [session-id (uuid/random)
              out        (th/command! {::th/type :update-file-branch-from-source
                                       ::rpc/profile-id (:id profile)
                                       ::rpc/session-id session-id
                                       :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= [fchanges/revision-kind] (kinds-of branch-file-id session-id)))))

      (t/testing "the merge"
        (apply-change* profile branch-file-id
                       {:type :add-color :color {:id (uuid/random) :name "Branch" :color "#0000ff" :opacity 1}})
        (let [session-id (uuid/random)
              out        (th/command! {::th/type :merge-file-branch
                                       ::rpc/profile-id (:id profile)
                                       ::rpc/session-id session-id
                                       :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status)))
          (t/is (= [fchanges/revision-kind] (kinds-of (:id file) session-id))))))))

;;; --- Apply a branch to the source and keep it open

(defn- apply-branch*
  [profile branch-id & {:as params}]
  (th/command! (merge {::th/type :apply-file-branch
                       ::rpc/profile-id (:id profile)
                       :branch-id branch-id}
                      params)))

(defn- add-color-change
  [id name]
  {:type :add-color :color {:id id :name name :color "#123456" :opacity 1}})

(defn- oplog-rows
  [branch-id]
  (th/db-query :file-branch-change {:branch-id branch-id}))

(defn- branch-row-of
  [profile file-id branch-id]
  (->> (th/command! {::th/type :get-file-branches
                     ::rpc/profile-id (:id profile)
                     :file-id file-id})
       :result
       (filter #(= branch-id (:id %)))
       (first)))

(defn- setup-branch*
  [name]
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        branch  (:result (th/command! {::th/type :create-file-branch
                                       ::rpc/profile-id (:id profile)
                                       :file-id (:id file)
                                       :name name}))]
    {:profile profile :file file :branch branch}))

(t/deftest apply-keeps-the-branch-open
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "keep-me")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)
          first-id       (uuid/random)
          second-id      (uuid/random)
          old-base       (:base-snapshot-id (th/db-get :file-branch {:id branch-id}))]

      (t/is (nil? (:error (apply-change* profile branch-file-id (add-color-change first-id "First")))))
      (let [branch-revn (:revn (th/db-get :file {:id branch-file-id}))
            out         (apply-branch* profile branch-id)]
        (t/is (nil? (:error out)))
        (t/is (= :applied (-> out :result :status)))

        (t/testing "the source gets the change"
          (t/is (contains? (colors-of profile (:id file)) first-id))
          (t/is (= (:revn (th/db-get :file {:id (:id file)})) (-> out :result :revn))))

        (t/testing "the branch stays open on top of the new source"
          (let [row (th/db-get :file-branch {:id branch-id})
                bf  (th/db-get :file {:id branch-file-id})]
            (t/is (= "open" (:status row)))
            (t/is (nil? (:deleted-at row)))
            (t/is (nil? (:deleted-at bf)))
            (t/is (not= old-base (:base-snapshot-id row)))
            (t/is (= (:revn (th/db-get :file {:id (:id file)})) (:base-source-revn row)))
            ;; nothing the source got had to be replayed on the branch, so its
            ;; open clients keep their revn
            (t/is (= branch-revn (:revn bf)))
            (t/is (= branch-revn (-> out :result :branch-revn)))))

        (t/testing "the branch reads the same document over an empty op log"
          (t/is (empty? (oplog-rows branch-id)))
          (t/is (contains? (colors-of profile branch-file-id) first-id)))

        (t/testing "the branch is in sync with the source"
          (let [row (branch-row-of profile (:id file) branch-id)]
            (t/is (= 0 (:ahead row)))
            (t/is (= 0 (:behind row)))
            (t/is (= 0 (:conflicts row)))))

        (t/testing "the superseded base is released and the source has a safety snapshot"
          (let [base (th/db-get :file-change {:id old-base} {::db/remove-deleted false})]
            (t/is (ct/is-before? (:deleted-at base) (ct/in-future {:days 365}))))
          (t/is (some #(some-> (:label %) (str/starts-with? "pre-apply/"))
                      (th/db-query :file-change {:file-id (:id file)})))))

      (t/testing "the branch keeps working: a later edit applies again"
        (t/is (nil? (:error (apply-change* profile branch-file-id (add-color-change second-id "Second")))))
        (t/is (= 1 (:ahead (branch-row-of profile (:id file) branch-id))))
        (let [out (apply-branch* profile branch-id)]
          (t/is (nil? (:error out)))
          (t/is (= :applied (-> out :result :status))))
        (t/is (= #{first-id second-id}
                 (into #{} (filter #{first-id second-id}) (colors-of profile (:id file))))))

      (t/testing "and can still be merged"
        (let [third-id (uuid/random)]
          (apply-change* profile branch-file-id (add-color-change third-id "Third"))
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status))))
          (t/is (contains? (colors-of profile (:id file)) third-id)))))))

(t/deftest apply-refuses-a-branch-behind-its-source
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "behind")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)
          source-color     (uuid/random)
          branch-color   (uuid/random)]
      (apply-change* profile (:id file) (add-color-change source-color "Main"))
      (apply-change* profile branch-file-id (add-color-change branch-color "Branch"))

      (let [source-revn (:revn (th/db-get :file {:id (:id file)}))
            out       (apply-branch* profile branch-id)]
        (t/is (= :validation (th/ex-type (:error out))))
        (t/is (= :branch-behind-source (th/ex-code (:error out))))
        (t/is (= source-revn (:revn (th/db-get :file {:id (:id file)}))))
        (t/is (not (contains? (colors-of profile (:id file)) branch-color))))

      (t/testing "once updated from the source, the apply goes through"
        (t/is (= :updated (-> (th/command! {::th/type :update-file-branch-from-source
                                            ::rpc/profile-id (:id profile)
                                            :branch-id branch-id})
                              :result :status)))
        (let [out (apply-branch* profile branch-id)]
          (t/is (nil? (:error out)))
          (t/is (= :applied (-> out :result :status))))
        (t/is (contains? (colors-of profile (:id file)) branch-color))
        (t/is (empty? (oplog-rows branch-id)))))))

(t/deftest apply-refusals-write-nothing
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "refusals")
          viewer   (th/create-profile* 2 {:is-active true})
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)
          color-id (uuid/random)]
      (apply-change* profile branch-file-id (add-color-change color-id "Only"))

      (t/testing "a stale expected source revn"
        (let [out (apply-branch* profile branch-id :expected-source-revn 9999)]
          (t/is (= :conflict (th/ex-type (:error out))))
          (t/is (= :file-modified (th/ex-code (:error out))))))

      (t/testing "every change left out"
        (let [out (apply-branch* profile branch-id
                                 :excluded #{(bm/change-key {:kind :color :id color-id})})]
          (t/is (= :nothing-to-merge (th/ex-code (:error out))))))

      (t/testing "a profile that cannot edit the source"
        (th/create-team-role* {:team-id (:default-team-id profile)
                               :profile-id (:id viewer)
                               :role :viewer})
        (th/create-file-role* {:file-id branch-file-id
                               :profile-id (:id viewer)
                               :role :editor})
        (t/is (some? (:error (apply-branch* viewer branch-id)))))

      (t/testing "an archived branch"
        (th/command! {::th/type :archive-file-branch
                      ::rpc/profile-id (:id profile)
                      :branch-id branch-id})
        (t/is (= :branch-not-open (th/ex-code (:error (apply-branch* profile branch-id))))))

      (t/testing "an unknown branch"
        (t/is (= :branch-not-found (th/ex-code (:error (apply-branch* profile (uuid/next)))))))

      (t/is (not (contains? (colors-of profile (:id file)) color-id)))
      (t/is (empty? (th/db-query :file-branch-merge {:file-id (:id file)}))))))

(t/deftest apply-with-exclusion-keeps-the-left-out-change-on-the-branch
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "selective-apply")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)
          keep-id (uuid/random)
          drop-id (uuid/random)]
      (apply-change* profile branch-file-id (add-color-change keep-id "Keep"))
      (apply-change* profile branch-file-id (add-color-change drop-id "Later"))

      (let [out (apply-branch* profile branch-id
                               :excluded #{(bm/change-key {:kind :color :id drop-id})})]
        (t/is (nil? (:error out)))
        (t/is (= :applied (-> out :result :status))))

      (t/testing "the source gets the selected change only"
        (let [colors (colors-of profile (:id file))]
          (t/is (contains? colors keep-id))
          (t/is (not (contains? colors drop-id)))))

      (t/testing "the branch keeps the change left out as its own work"
        (let [colors (colors-of profile branch-file-id)]
          (t/is (contains? colors keep-id))
          (t/is (contains? colors drop-id)))
        (t/is (= 1 (count (oplog-rows branch-id))))
        (let [row (branch-row-of profile (:id file) branch-id)]
          (t/is (= 1 (:ahead row)))
          (t/is (= 0 (:behind row)))))

      (t/testing "a later apply takes it"
        (t/is (= :applied (-> (apply-branch* profile branch-id) :result :status)))
        (t/is (contains? (colors-of profile (:id file)) drop-id))
        (t/is (empty? (oplog-rows branch-id)))))))

(t/deftest apply-copies-branch-media-into-the-source
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "apply-media")
          branch-id      (:id branch)
          branch-file-id (:branch-file-id branch)
          storage (-> (:app.storage/storage th/*system*)
                      (assoc :app.storage/backend :fs))
          sobj    (sto/put-object! storage {::sto/content (sto/content "branch-image")
                                            :bucket :file-media-object
                                            :content-type "image/png"})
          fmo     (th/create-file-media-object* {:file-id branch-file-id
                                                 :name "new.png"
                                                 :media-id (:id sobj)})
          source-rows (fn []
                        (->> (th/db-query :file-media-object {:file-id (:id file)})
                             (filterv #(= (:id sobj) (:media-id %)))))]
      (t/is (nil? (:error (apply-change* profile branch-file-id
                                         {:type :add-media
                                          :object {:id (:id fmo) :name "new.png"
                                                   :media-id (:id sobj)
                                                   :width 100 :height 100 :mtype "image/png"}}))))

      (t/is (= :applied (-> (apply-branch* profile branch-id) :result :status)))

      (t/testing "the source references a row it owns"
        (let [media (-> (th/command! {::th/type :get-file
                                      ::rpc/profile-id (:id profile)
                                      :id (:id file)})
                        :result :data :media)
              added (->> (vals media) (filter #(= "new.png" (:name %))) first)]
          (t/is (some? added))
          (t/is (not= (:id fmo) (:id added)))
          (t/is (= (:id file) (:file-id (th/db-get :file-media-object {:id (:id added)}))))))

      (t/testing "the branch still shows its own row and is in sync"
        (let [media (-> (th/command! {::th/type :get-file
                                      ::rpc/profile-id (:id profile)
                                      :id branch-file-id})
                        :result :data :media)]
          (t/is (contains? media (:id fmo))))
        (let [row (branch-row-of profile (:id file) branch-id)]
          (t/is (= 0 (:ahead row)))
          (t/is (= 0 (:behind row)))))

      (t/testing "a second apply copies nothing again"
        (apply-change* profile branch-file-id (add-color-change (uuid/random) "After"))
        (t/is (= :applied (-> (apply-branch* profile branch-id) :result :status)))
        (t/is (= 1 (count (source-rows))))))))

(t/deftest apply-records-itself-in-the-merges-of-the-source
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "recorded")]
      (apply-change* profile (:branch-file-id branch) (add-color-change (uuid/random) "C"))
      (t/is (= :applied (-> (apply-branch* profile (:id branch) :comment "  First drop  ")
                            :result :status)))
      (let [merges (:result (th/command! {::th/type :get-file-branch-merges
                                          ::rpc/profile-id (:id profile)
                                          :file-id (:id file)}))]
        (t/is (= ["apply"] (mapv :kind merges)))
        (t/is (= ["recorded"] (mapv :branch-name merges)))
        (t/is (= ["First drop"] (mapv :comment merges)))))))

(t/deftest apply-notifies-only-after-the-commit
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile file branch]} (setup-branch* "notify-apply")
          seen (atom [])]
      (apply-change* profile (:branch-file-id branch) (add-color-change (uuid/random) "C"))
      (with-redefs [mbus/pub!
                    (fn [_ & {:keys [topic message]}]
                      (swap! seen conj {:topic topic
                                        :message message
                                        :source-revn (:revn (th/db-get :file {:id (:id file)}))}))]
        (t/is (= :file-modified (-> (apply-branch* profile (:id branch) :expected-source-revn 9999)
                                    :error ex-data :code)))
        (t/is (empty? @seen))

        (t/is (= :applied (-> (apply-branch* profile (:id branch)) :result :status)))
        ;; the source's clients reload; the branch did not move, so its clients
        ;; hear nothing
        (let [[{:keys [topic message source-revn]} :as all] @seen]
          (t/is (= 1 (count all)))
          (t/is (= (:id file) topic))
          (t/is (= :file-merged (:type message)))
          (t/is (= (:revn message) source-revn)))))))

(t/deftest apply-serializes-against-a-concurrent-delete
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile branch]} (setup-branch* "gone")]
      (apply-change* profile (:branch-file-id branch) (add-color-change (uuid/random) "C"))
      (t/is (nil? (:error (th/command! {::th/type :delete-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :branch-id (:id branch)}))))
      (let [out (apply-branch* profile (:id branch))]
        (t/is (some? (:error out)))
        (t/is (contains? #{:branch-not-found :branch-not-open} (th/ex-code (:error out))))))))
