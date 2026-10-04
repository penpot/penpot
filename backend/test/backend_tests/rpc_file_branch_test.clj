;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-branch-test
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]))

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
