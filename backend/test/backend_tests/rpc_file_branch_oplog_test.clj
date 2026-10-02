;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-branch-oplog-test
  "The op-log storage model: a branch file stores no data payload. Its
  `:data` is derived on read by replaying `file_branch_change` over the
  pinned merge-base snapshot, and branch saves append ops instead of
  persisting file data. These tests assert the storage and derivation
  invariants on top of the behaviour covered by
  `backend-tests.rpc-file-branch-test`."
  (:require
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as fmg]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- apply-change* [profile file-id change]
  (let [f (th/db-get :file {:id file-id})]
    (th/command! {::th/type :update-file
                  ::rpc/profile-id (:id profile)
                  :id file-id
                  :session-id (uuid/random)
                  :revn (:revn f)
                  :vern (:vern f)
                  :features cfeat/supported-features
                  :changes [change]})))

(defn- create-branch*
  [profile file-id name]
  (:result (th/command! {::th/type :create-file-branch
                         ::rpc/profile-id (:id profile)
                         :file-id file-id
                         :name name})))

(defn- oplog-rows [branch-file-id]
  (th/db-query :file-branch-change {:file-id branch-file-id}))

;; only "main" rows: the auto-file-snapshot machinery appends "snapshot"
;; rows on every save, which are checkpoints, not a stored data payload
(defn- stored-data-rows [file-id]
  (->> (th/db-query :file-data {:file-id file-id})
       (filterv #(= "main" (:type %)))))

(t/deftest branch-stores-no-data-and-derives-state
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-empty")
          branch-file-id (:branch-file-id create)]

      (t/testing "the branch file stores no real data"
        (t/is (empty? (stored-data-rows branch-file-id))))

      (t/testing "the op log starts empty"
        (t/is (empty? (oplog-rows branch-file-id))))

      (t/testing "the derived state equals main"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (let [mf (th/command! {::th/type :get-file
                                 ::rpc/profile-id (:id profile)
                                 :id (:id file)})]
            (t/is (= (-> mf :result :data :pages-index)
                     (-> out :result :data :pages-index)))))))))

(t/deftest branch-save-appends-ops-and-never-persists-data
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-save")
          branch-file-id (:branch-file-id create)
          color-id (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id color-id :name "C" :color "#112233" :opacity 1}})

      (t/testing "one op log row per save"
        (let [rows (oplog-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (= 1 (:revn (first rows))))
          (t/is (= [{:type :add-color
                     :color {:id color-id :name "C" :color "#112233" :opacity 1}}]
                   (blob/decode (:changes (first rows)))))))

      (t/testing "the stored data payload never grew"
        (t/is (empty? (stored-data-rows branch-file-id))))

      (t/testing "the derived state carries the branch edit"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (contains? (-> out :result :data :colors) color-id)))))))

(t/deftest update-from-main-squashes-the-op-log
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-update")
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)
          main-color (uuid/random)
          branch-color (uuid/random)]

      ;; main gains a color after the fork
      (apply-change* profile (:id file)
                     {:type :add-color
                      :color {:id main-color :name "Main" :color "#00ff00" :opacity 1}})

      ;; the branch gains its own color (so the squash is non-empty)
      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id branch-color :name "Branch" :color "#ff0000" :opacity 1}})

      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (t/testing "the op log is replaced by the branch-only net"
        (let [rows (oplog-rows branch-file-id)
              ops  (-> rows first :changes blob/decode)]
          (t/is (= 1 (count rows)))
          (t/is (some #(and (= :add-color (:type %))
                            (= branch-color (get-in % [:color :id])))
                      ops))
          (t/is (not-any? #(and (= :add-color (:type %))
                                (= main-color (get-in % [:color :id])))
                          ops))))

      (t/testing "the derived state has both colors, main's through the base"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (let [colors (-> out :result :data :colors)]
            (t/is (contains? colors main-color))
            (t/is (contains? colors branch-color)))))

      (t/testing "the merge base moved to main's revn"
        (let [[row] (th/db-query :file-branch {:id branch-id})
              mf    (th/db-get :file {:id (:id file)})]
          (t/is (= (:revn mf) (:base-revn row))))))))

(t/deftest merge-with-keep-branch-leaves-the-branch-readable
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-keep")
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)
          color-id (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id color-id :name "C" :color "#112233" :opacity 1}})

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :keep-branch true})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (t/testing "the kept branch still derives its state"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (contains? (-> out :result :data :colors) color-id))))

      (t/testing "the base pin survives while the branch is kept"
        (let [rows (->> (th/db-query :file-change {:file-id (:id file)})
                        (filter #(some-> (:label %) (str/starts-with? "branch-base/"))))]
          (t/is (seq rows))
          (t/is (every? (fn [{:keys [deleted-at]}]
                          (or (nil? deleted-at)
                              (ct/is-after? deleted-at (ct/in-future {:days 400}))))
                        rows)))))))

(t/deftest update-from-main-compares-in-main-frame
  ;; A branch stores no data: its document is the base snapshot plus the op
  ;; log, so every reference it inherited names MAIN. A local reference is a
  ;; self reference in both files, because a branch keeps main's colours and
  ;; typographies under the same entity ids, so the comparison must run in
  ;; main's frame and the change that lands in the branch must still name the
  ;; branch.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          file     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          main-id  (:id file)
          page-id  (-> (th/command! {::th/type :get-file
                                     ::rpc/profile-id (:id profile)
                                     :id main-id})
                       :result :data :pages first)
          color-id (uuid/random)
          shape-id (uuid/random)
          fills    (fn [ref-file color]
                     [{:fill-color color
                       :fill-opacity 1
                       :fill-color-ref-id color-id
                       :fill-color-ref-file ref-file}])]

      ;; main publishes a colour of its own and one of its shapes uses it, so
      ;; the shape carries main's file id in a self reference
      (apply-change* profile main-id
                     {:type :add-color
                      :color {:id color-id :name "Brand" :color "#ff0000" :opacity 1}})
      (apply-change* profile main-id
                     {:type :add-obj
                      :page-id page-id
                      :id shape-id
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj (-> (cts/setup-shape
                                {:id shape-id :name "Referenced" :type :rect
                                 :parent-id uuid/zero :frame-id uuid/zero})
                               (assoc :fills (fills main-id "#ff0000")))})

      (let [create         (create-branch* profile main-id "main-frame")
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)]

        (t/testing "main's repaint is not a conflict, and is not logged as a branch change"
          (apply-change* profile main-id
                         {:type :mod-obj
                          :page-id page-id
                          :id shape-id
                          :operations [{:type :set :attr :fills :val (fills main-id "#00ff00")}]})
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))
          (let [out (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id})]
            (t/is (= "#00ff00" (get-in out [:result :data :pages-index page-id
                                            :objects shape-id :fills 0 :fill-color]))))
          ;; main's own change arrives through the repositioned base, so the
          ;; net the update writes is empty. A ref left in main's frame would
          ;; make this shape differ from the new base and put it in the log.
          (t/is (empty? (oplog-rows branch-file-id))))

        (t/testing "a genuine conflict still reports"
          (apply-change* profile main-id
                         {:type :mod-obj
                          :page-id page-id
                          :id shape-id
                          :operations [{:type :set :attr :fills :val (fills main-id "#0000ff")}]})
          (apply-change* profile branch-file-id
                         {:type :mod-obj
                          :page-id page-id
                          :id shape-id
                          :operations [{:type :set :attr :fills :val (fills branch-file-id "#ffffff")}]})
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= 1 (count (-> out :result :conflicts))))
            (t/is (= shape-id (-> out :result :conflicts first :id)))))

        (t/testing "taking main keeps the branch writeable"
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :resolutions {shape-id :main}})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))
          (let [out (th/command! {::th/type :get-file
                                  ::rpc/profile-id (:id profile)
                                  :id branch-file-id})]
            (t/is (= "#0000ff" (get-in out [:result :data :pages-index page-id
                                            :objects shape-id :fills 0 :fill-color])))))))))

(t/deftest update-resolutions-are-document-keyed
  ;; A resolution names a DOCUMENT, not the role the merge engine happened to
  ;; hand it. `branch_merge.cljc::compute-merge` reports every conflict with
  ;; main's value under `:main` and the branch's under `:branch` in both
  ;; directions, and the command applies each resolution in those same terms
  ;; (`files_branch.clj::update-branch-from-main` inverts it before
  ;; `branch_merge.cljc::compute-changes`, which runs with main as the source
  ;; and the branch as the target). This test pins that contract at the
  ;; command boundary: `:main` brings main's value into the
  ;; branch, `:branch` keeps the branch's value, and a per-attribute map
  ;; obeys the same vocabulary for the attribute it names.
  ;;
  ;; Each fixture also gives main one non-conflicting change, and it has to:
  ;; a resolution that keeps the branch's own side leaves the engine nothing
  ;; to apply for that entity, and an update with an empty change set takes
  ;; the command's no-op path (`files_branch.clj::update-branch-from-main`
  ;; discards the branch's op log there and moves the merge base to main).
  ;; Without a main-side change, every resolution would leave the branch
  ;; carrying main's value, so the two `:main` cases below could pass while
  ;; the resolution was ignored. Main's extra change keeps the write path
  ;; exercised, and every case below fails if the mapping is inverted.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile     (th/create-profile* 1 {:is-active true})
          proj-id     (:default-project-id profile)
          branch-fill "#bb0000"
          main-fill   "#00bb00"
          fills       (fn [color] [{:fill-color color :fill-opacity 1}])
          fixture     (fn [idx name]
                        (let [file     (th/create-file* idx {:profile-id (:id profile)
                                                             :project-id proj-id
                                                             :is-shared false})
                              main-id  (:id file)
                              page-id  (-> (th/command! {::th/type :get-file
                                                         ::rpc/profile-id (:id profile)
                                                         :id main-id})
                                           :result :data :pages first)
                              shape-id (uuid/random)
                              color-id (uuid/random)]

                          ;; the shape enters the merge base before the fork
                          (apply-change* profile main-id
                                         {:type :add-obj
                                          :page-id page-id
                                          :id shape-id
                                          :parent-id uuid/zero
                                          :frame-id uuid/zero
                                          :obj (-> (cts/setup-shape
                                                    {:id shape-id :name "Coded" :type :rect
                                                     :parent-id uuid/zero :frame-id uuid/zero})
                                                   (assoc :fills (fills "#000000")))})

                          (let [create (create-branch* profile main-id name)]
                            ;; the same attribute diverges on the two sides
                            (apply-change* profile (:branch-file-id create)
                                           {:type :mod-obj
                                            :page-id page-id
                                            :id shape-id
                                            :operations [{:type :set :attr :fills
                                                          :val (fills branch-fill)}]})
                            (apply-change* profile main-id
                                           {:type :mod-obj
                                            :page-id page-id
                                            :id shape-id
                                            :operations [{:type :set :attr :fills
                                                          :val (fills main-fill)}]})
                            ;; main also gains a change the branch never touched,
                            ;; so the update has something of main's to apply
                            (apply-change* profile main-id
                                           {:type :add-color
                                            :color {:id color-id :name "Main-only"
                                                    :color "#336699" :opacity 1}})
                            {:branch-id      (:id create)
                             :branch-file-id (:branch-file-id create)
                             :page-id        page-id
                             :shape-id       shape-id})))
          fill-of     (fn [{:keys [branch-file-id page-id shape-id]}]
                        (get-in (th/command! {::th/type :get-file
                                              ::rpc/profile-id (:id profile)
                                              :id branch-file-id})
                                [:result :data :pages-index page-id :objects shape-id
                                 :fills 0 :fill-color]))
          update!     (fn [{:keys [branch-id]} resolutions]
                        (th/command! {::th/type :update-branch-from-main
                                      ::rpc/profile-id (:id profile)
                                      :branch-id branch-id
                                      :resolutions resolutions}))]

      (t/testing "a whole-entity :main resolution applies main's value"
        (let [fx  (fixture 1 "res-doc-main")
              out (update! fx {(:shape-id fx) :main})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status)))
          (t/is (= main-fill (fill-of fx)))))

      (t/testing "a whole-entity :branch resolution keeps the branch's value"
        (let [fx  (fixture 2 "res-doc-branch")
              out (update! fx {(:shape-id fx) :branch})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status)))
          (t/is (= branch-fill (fill-of fx)))))

      (t/testing "a per-attribute :main resolution applies main's value to that attribute"
        (let [fx  (fixture 3 "res-doc-attr")
              out (update! fx {(:shape-id fx) {:fills :main}})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status)))
          (t/is (= main-fill (fill-of fx))))))))

;; A branch stores no data payload, so its op log IS its work: an update
;; from main that discards that log without having folded the work into
;; the new base destroys it. `files_branch.clj::update-branch-from-main`
;; has a cheap path for a branch that is already in sync with main, and
;; the three tests below pin both directions of it. Two of them reproduce
;; an observed loss, and the third pins the case the cheap path is for.

(t/deftest update-from-main-keeps-a-branch-edit-when-main-has-none
  ;; Main holds nothing of its own here, so
  ;; `branch_merge.cljc::compute-changes` returns an empty change set and
  ;; the command reached its cheap path on that result alone. The branch's
  ;; own edit lived only in the op log, so discarding the log reverted the
  ;; shape to the base's fill. Removing this test lets that loss come back
  ;; through the "main has nothing to say" door.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile     (th/create-profile* 1 {:is-active true})
          proj-id     (:default-project-id profile)
          branch-fill "#bb0000"
          fills       (fn [color] [{:fill-color color :fill-opacity 1}])
          file        (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id
                                          :is-shared false})
          main-id     (:id file)
          page-id     (-> (th/command! {::th/type :get-file
                                        ::rpc/profile-id (:id profile)
                                        :id main-id})
                          :result :data :pages first)
          shape-id    (uuid/random)]

      ;; the shape enters the base before the fork, so the branch inherits it
      (apply-change* profile main-id
                     {:type :add-obj
                      :page-id page-id
                      :id shape-id
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj (-> (cts/setup-shape
                                {:id shape-id :name "Branch-only" :type :rect
                                 :parent-id uuid/zero :frame-id uuid/zero})
                               (assoc :fills (fills "#000000")))})

      (let [create         (create-branch* profile main-id "fast-path-own-edit")
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)
            read-fill      (fn []
                             (get-in (th/command! {::th/type :get-file
                                                   ::rpc/profile-id (:id profile)
                                                   :id branch-file-id})
                                     [:result :data :pages-index page-id :objects shape-id
                                      :fills 0 :fill-color]))]

        ;; the branch's only edit, and main never moves after the fork
        (apply-change* profile branch-file-id
                       {:type :mod-obj
                        :page-id page-id
                        :id shape-id
                        :operations [{:type :set :attr :fills :val (fills branch-fill)}]})
        (t/is (= branch-fill (read-fill)))

        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (t/testing "the branch still derives its own edit from its log"
          (t/is (= branch-fill (read-fill))))))))

(t/deftest update-from-main-keeps-the-branch-on-a-branch-resolution
  ;; A conflict resolved to the branch removes the entity from the change
  ;; set, because the branch's value is already the one that must survive.
  ;; Main's repaint is the only change in play, so the change set came out
  ;; empty and the cheap path discarded the log that held the branch's own
  ;; value. Removing this test lets the resolution path lose the branch's
  ;; side while every other resolution case stays green.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile     (th/create-profile* 1 {:is-active true})
          proj-id     (:default-project-id profile)
          branch-fill "#bb0000"
          main-fill   "#00bb00"
          fills       (fn [color] [{:fill-color color :fill-opacity 1}])
          file        (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id
                                          :is-shared false})
          main-id     (:id file)
          page-id     (-> (th/command! {::th/type :get-file
                                        ::rpc/profile-id (:id profile)
                                        :id main-id})
                          :result :data :pages first)
          shape-id    (uuid/random)]

      (apply-change* profile main-id
                     {:type :add-obj
                      :page-id page-id
                      :id shape-id
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj (-> (cts/setup-shape
                                {:id shape-id :name "Contested" :type :rect
                                 :parent-id uuid/zero :frame-id uuid/zero})
                               (assoc :fills (fills "#000000")))})

      (let [create         (create-branch* profile main-id "fast-path-conflict")
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)
            read-fill      (fn []
                             (get-in (th/command! {::th/type :get-file
                                                   ::rpc/profile-id (:id profile)
                                                   :id branch-file-id})
                                     [:result :data :pages-index page-id :objects shape-id
                                      :fills 0 :fill-color]))]

        ;; both sides repaint the same shape, and nothing else changes
        (apply-change* profile branch-file-id
                       {:type :mod-obj
                        :page-id page-id
                        :id shape-id
                        :operations [{:type :set :attr :fills :val (fills branch-fill)}]})
        (apply-change* profile main-id
                       {:type :mod-obj
                        :page-id page-id
                        :id shape-id
                        :operations [{:type :set :attr :fills :val (fills main-fill)}]})

        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id
                                :resolutions {shape-id :branch}})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (t/testing "the resolution keeps the branch's value after the update"
          (t/is (= branch-fill (read-fill))))))))

(t/deftest update-from-main-catches-a-branch-with-no-work-up-cheaply
  ;; The cheap path's own case: main advanced its revision, the branch
  ;; holds no work of its own, and the engine finds nothing of main's to
  ;; apply, so the repositioned base alone reproduces the branch's state.
  ;; A branch that is behind main in CONTENT does not reach this path, for
  ;; `branch_merge.cljc::compute-changes` reports main's own changes as the
  ;; set the update must apply, and the command then takes the squash
  ;; below. Removing this test lets the cheap path
  ;; (`files_branch.clj::update-branch-from-main`) be widened or dropped
  ;; until every such update pays for a branch snapshot, a branch revision,
  ;; and a rewrite of an empty log.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile    (th/create-profile* 1 {:is-active true})
          proj-id    (:default-project-id profile)
          fills      (fn [color] [{:fill-color color :fill-opacity 1}])
          file       (th/create-file* 1 {:profile-id (:id profile)
                                         :project-id proj-id
                                         :is-shared false})
          main-id    (:id file)
          page-id    (-> (th/command! {::th/type :get-file
                                       ::rpc/profile-id (:id profile)
                                       :id main-id})
                         :result :data :pages first)
          shape-id   (uuid/random)
          ;; an edit that changes nothing: main's revision moves, its
          ;; content does not, so the update has nothing of main's to apply
          bump-main! (fn []
                       (let [f (th/db-get :file {:id main-id})]
                         (th/command! {::th/type :update-file
                                       ::rpc/profile-id (:id profile)
                                       :id main-id
                                       :session-id (uuid/random)
                                       :revn (:revn f)
                                       :vern (:vern f)
                                       :features cfeat/supported-features
                                       :changes []})))]

      (apply-change* profile main-id
                     {:type :add-obj
                      :page-id page-id
                      :id shape-id
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj (-> (cts/setup-shape
                                {:id shape-id :name "Inherited" :type :rect
                                 :parent-id uuid/zero :frame-id uuid/zero})
                               (assoc :fills (fills "#000000")))})

      (let [create         (create-branch* profile main-id "fast-path-no-work")
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)
            revn-before    (:revn (th/db-get :file {:id branch-file-id}))
            xlog-before    (count (th/db-query :file-change {:file-id branch-file-id}))
            base-before    (th/db-get :file-branch {:id branch-id})
            derive         (fn [file-id]
                             (-> (th/command! {::th/type :get-file
                                               ::rpc/profile-id (:id profile)
                                               :id file-id})
                                 :result :data :pages-index))]

        ;; main moves on in revision terms, and the branch still holds no
        ;; work of its own
        (bump-main!)

        (t/testing "the branch is behind main before the update"
          (t/is (pos? (- (:revn (th/db-get :file {:id main-id}))
                         (:base-revn base-before)))))

        (let [out (th/command! {::th/type :update-branch-from-main
                                ::rpc/profile-id (:id profile)
                                :branch-id branch-id})]
          (t/is (nil? (:error out)))
          (t/is (= :updated (-> out :result :status))))

        (t/testing "the branch's derived state is main's"
          (t/is (= (derive main-id) (derive branch-file-id))))

        (t/testing "the merge base moved to main's current revision"
          (let [base-after (th/db-get :file-branch {:id branch-id})
                main-file  (th/db-get :file {:id main-id})]
            (t/is (= (:revn main-file) (:base-revn base-after)))
            (t/is (not= (:base-snapshot-id base-before) (:base-snapshot-id base-after)))))

        (t/testing "the branch was not rewritten to catch up"
          (t/is (= revn-before (:revn (th/db-get :file {:id branch-file-id}))))
          (t/is (= xlog-before (count (th/db-query :file-change {:file-id branch-file-id})))))
        (t/testing "the branch still stores no data payload"
          (t/is (empty? (stored-data-rows branch-file-id))))))))

;;; --- Regression tests

(t/deftest create-checks-the-branch-quota-inside-the-lock
  ;; Two designers racing for the LAST branch slot of one file: the
  ;; loser is refused only if its quota count runs inside the
  ;; transaction, after the advisory lock, where it sees the winner's
  ;; committed branch. A count taken outside the lock lets both creates
  ;; read "one slot left" and both insert.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile1  (th/create-profile* 1 {:is-active true})
          profile2  (th/create-profile* 2 {:is-active true})
          proj-id   (:default-project-id profile1)
          file      (th/create-file* 1 {:profile-id (:id profile1)
                                        :project-id proj-id
                                        :is-shared false})
          file-id   (:id file)
          ;; the racer is a second designer: the per-profile climit
          ;; serializes one designer's creates against themselves
          _         (th/create-file-role* {:file-id file-id
                                           :profile-id (:id profile2)
                                           :role :editor})
          real-lock db/xact-lock!
          raced?    (atom false)]

      (with-redefs [cf/get (th/config-get-mock {:quotes-branches-per-file 1})
                    db/xact-lock!
                    (fn [conn n]
                      ;; on first entry into the transaction the racer
                      ;; runs to completion (its own transaction, its own
                      ;; connection, committed) BEFORE the outer create
                      ;; takes the advisory lock
                      (when (compare-and-set! raced? false true)
                        (t/is (nil? (:error (th/command!
                                             {::th/type :create-file-branch
                                              ::rpc/profile-id (:id profile2)
                                              :file-id file-id
                                              :name "racer"})))))
                      (real-lock conn n))]
        (let [out  (th/command! {::th/type :create-file-branch
                                 ::rpc/profile-id (:id profile1)
                                 :file-id file-id
                                 :name "outer"})
              data (ex-data (:error out))
              rows (th/db-query :file-branch {:source-file-id file-id})]

          (t/testing "the loser is refused with the quota error"
            (t/is (some? (:error out)))
            (t/is (= :restriction (:type data)))
            (t/is (= :max-quote-reached (:code data))))

          (t/testing "exactly the racer's branch exists"
            (t/is (= 1 (count rows)))
            (t/is (= (:id profile2) (:created-by (first rows))))))))))

(t/deftest create-refuses-a-blank-branch-name
  ;; `::update-file-branch` refuses a blank name; creation used to
  ;; accept one, leaving behind a branch the rename would reject.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          out     (th/command! {::th/type :create-file-branch
                                ::rpc/profile-id (:id profile)
                                :file-id (:id file)
                                :name "   "})
          data    (ex-data (:error out))]
      (t/is (some? (:error out)))
      (t/is (= :validation (:type data)))
      (t/is (= :invalid-branch-name (:code data)))
      (t/is (empty? (th/db-query :file-branch {:source-file-id (:id file)}))))))

(t/deftest create-writes-no-file-data-row
  ;; The create docstring promises a branch file with NO data payload:
  ;; a payload on a branch file invites a third writer into a derived
  ;; state that already has two. Only materializing the branch into an
  ;; ordinary file creates it.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "no-payload")
          branch-file-id (:branch-file-id create)
          color-id       (uuid/random)]

      (t/testing "creation writes no file_data row at all"
        (t/is (empty? (th/db-query :file-data {:file-id branch-file-id}))))

      (t/testing "the branch still saves to its op log, without a payload"
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id color-id :name "C" :color "#112233" :opacity 1}})
        (t/is (= 1 (count (oplog-rows branch-file-id))))
        (t/is (empty? (stored-data-rows branch-file-id))))

      (t/testing "the branch still reads"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (contains? (-> out :result :data :colors) color-id))))

      (t/testing "materialize is what creates the payload row"
        (let [out (th/command! {::th/type :materialize-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-file-id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (= :materialized (-> out :result :status))))
        (t/is (some? (th/db-get :file-data {:file-id branch-file-id :type "main"})))))))

;;; --- Data versions across an upgrade
;;
;; A test-only file migration stands for a Penpot upgrade. Rebinding
;; `fmg/available-migrations` to the real list plus this name is the
;; upgrade: files written before the rebinding sit at the old data
;; version. The migration turns the color group separator "/" into " / "
;; and, like every real migration, assumes its input is at the old
;; version, so it corrupts a path already in the new format. A replay
;; that applies a segment at the wrong version shows up as a doubled
;; separator or an unspaced one.

(def ^:private test-migration "9999-test-spaced-color-path")

(defmethod fmg/migrate-data "9999-test-spaced-color-path"
  [data _]
  (d/update-when data :colors update-vals
                 (fn [color]
                   (d/update-when color :path str/replace "/" " / "))))

(defn- read-file
  [profile file-id]
  (let [out (th/command! {::th/type :get-file
                          ::rpc/profile-id (:id profile)
                          :id file-id})]
    (t/is (nil? (:error out)))
    (:result out)))

;; `:modified-at` is the time each apply touched the color, which differs
;; between two files that went through the same history
(defn- colors-of
  [file]
  (update-vals (-> file :data :colors) #(dissoc % :modified-at)))

(t/deftest branch-replays-each-op-at-the-data-version-it-was-written-at
  ;; The reference is an ordinary file that goes through the same history:
  ;; an op before the upgrade, the upgrade, an op after it. An ordinary
  ;; file is migrated between the two ops, so the branch derive must
  ;; migrate its document at the same point.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          main     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          ordinary (th/create-file* 2 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          upgraded (conj fmg/available-migrations test-migration)
          c1       (uuid/random)
          c2       (uuid/random)
          c3       (uuid/random)
          seed     {:type :add-color
                    :color {:id c1 :name "One" :path "Brand/One" :color "#111111" :opacity 1}}
          old-op   {:type :add-color
                    :color {:id c2 :name "Two" :path "Brand/Two" :color "#222222" :opacity 1}}
          ;; written by an editor that read the migrated document
          new-op   {:type :add-color
                    :color {:id c3 :name "Three" :path "Brand / Three" :color "#333333" :opacity 1}}]

      (apply-change* profile (:id main) seed)
      (apply-change* profile (:id ordinary) seed)

      (let [branch-file-id (:branch-file-id (create-branch* profile (:id main) "straddle"))]
        (apply-change* profile branch-file-id old-op)
        (apply-change* profile (:id ordinary) old-op)

        (with-redefs [fmg/available-migrations upgraded]
          (apply-change* profile branch-file-id new-op)
          (apply-change* profile (:id ordinary) new-op)

          (let [expected (colors-of (read-file profile (:id ordinary)))
                derived  (colors-of (read-file profile branch-file-id))]
            (t/testing "the reference holds every path in the new format"
              (t/is (= ["Brand / One" "Brand / Two" "Brand / Three"]
                       (mapv #(get-in expected [% :path]) [c1 c2 c3]))))

            (t/testing "the branch derives the document the ordinary file holds"
              (t/is (= expected derived)))))))))

(t/deftest merge-after-an-upgrade-compares-against-a-migrated-base
  ;; The branch renames a color before the upgrade and main never touches
  ;; it. After the upgrade main and the branch read migrated, so the base
  ;; must read migrated too, or main's migrated path reads as an edit and
  ;; the rename as a modify-modify conflict.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          main     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          upgraded (conj fmg/available-migrations test-migration)
          c1       (uuid/random)]

      (apply-change* profile (:id main)
                     {:type :add-color
                      :color {:id c1 :name "One" :path "Brand/One" :color "#111111" :opacity 1}})

      (let [create (create-branch* profile (:id main) "rename-before-upgrade")]
        (apply-change* profile (:branch-file-id create)
                       {:type :mod-color
                        :color {:id c1 :name "Uno" :path "Brand/One" :color "#111111" :opacity 1}})

        (with-redefs [fmg/available-migrations upgraded]
          (t/testing "the compare reports the rename as a clean change"
            (let [out (th/command! {::th/type :get-branch-diff
                                    ::rpc/profile-id (:id profile)
                                    :branch-id (:id create)})]
              (t/is (nil? (:error out)))
              (t/is (= 0 (-> out :result :stats :conflicts)))))

          (t/testing "the merge reports no conflict and applies the rename"
            (let [out (th/command! {::th/type :merge-file-branch
                                    ::rpc/profile-id (:id profile)
                                    :branch-id (:id create)})]
              (t/is (nil? (:error out)))
              (t/is (= :merged (-> out :result :status)))
              (t/is (empty? (-> out :result :conflicts))))
            (t/is (= {:name "Uno" :path "Brand / One"}
                     (-> (colors-of (read-file profile (:id main)))
                         (get c1)
                         (select-keys [:name :path]))))))))))

(t/deftest steady-state-branch-reads-run-no-migration
  ;; No upgrade since the branch was cut: base, op log, main and branch
  ;; share one data version, so reading, comparing and merging the branch
  ;; must not run a migration, not even an empty one (the schema check at
  ;; the end of `fmg/migrate` walks the whole document).
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          main    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id main) "steady")
          branch-file-id (:branch-file-id create)
          real-migrate      fmg/migrate
          real-migrate-data fmg/migrate-data
          calls   (atom 0)]

      (with-redefs [fmg/migrate      (fn [& args]
                                       (swap! calls inc)
                                       (apply real-migrate args))
                    fmg/migrate-data (fn [data id]
                                       (swap! calls inc)
                                       (real-migrate-data data id))]
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id (uuid/random) :name "A" :path "Brand/A" :color "#111111" :opacity 1}})
        (apply-change* profile branch-file-id
                       {:type :add-color
                        :color {:id (uuid/random) :name "B" :path "Brand/B" :color "#222222" :opacity 1}})
        (apply-change* profile (:id main)
                       {:type :add-color
                        :color {:id (uuid/random) :name "M" :path "Brand/M" :color "#333333" :opacity 1}})

        (read-file profile branch-file-id)
        (t/is (= :updated (-> (th/command! {::th/type :update-branch-from-main
                                            ::rpc/profile-id (:id profile)
                                            :branch-id (:id create)})
                              :result :status)))
        (t/is (nil? (:error (th/command! {::th/type :get-branch-diff
                                          ::rpc/profile-id (:id profile)
                                          :branch-id (:id create)}))))
        (let [out (th/command! {::th/type :merge-file-branch
                                ::rpc/profile-id (:id profile)
                                :branch-id (:id create)})]
          (t/is (nil? (:error out)))
          (t/is (= :merged (-> out :result :status))))

        (t/is (zero? @calls))))))

(t/deftest update-from-main-after-an-upgrade-compares-against-a-migrated-base
  ;; Same history as the merge case, integrated the other way: main's
  ;; migrated path must not read as a main-side edit of the color the
  ;; branch renamed.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          main     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          upgraded (conj fmg/available-migrations test-migration)
          c1       (uuid/random)
          c2       (uuid/random)]

      (apply-change* profile (:id main)
                     {:type :add-color
                      :color {:id c1 :name "One" :path "Brand/One" :color "#111111" :opacity 1}})

      (let [create (create-branch* profile (:id main) "update-after-upgrade")]
        (apply-change* profile (:branch-file-id create)
                       {:type :mod-color
                        :color {:id c1 :name "Uno" :path "Brand/One" :color "#111111" :opacity 1}})

        (with-redefs [fmg/available-migrations upgraded]
          ;; main's own work after the upgrade gives the update something
          ;; to bring in
          (apply-change* profile (:id main)
                         {:type :add-color
                          :color {:id c2 :name "Two" :path "Brand / Two" :color "#222222" :opacity 1}})

          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id (:id create)})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status)))
            (t/is (empty? (-> out :result :conflicts))))

          (t/testing "the branch keeps its rename and gains main's color"
            (let [colors (colors-of (read-file profile (:branch-file-id create)))]
              (t/is (= {:name "Uno" :path "Brand / One"}
                       (select-keys (get colors c1) [:name :path])))
              (t/is (= {:name "Two" :path "Brand / Two"}
                       (select-keys (get colors c2) [:name :path]))))))))))

(t/deftest branch-replays-an-unstamped-row-at-the-base-version
  ;; A row written before `file_branch_change.data_version` existed has
  ;; no stamp. Such rows precede every stamped row and were written over
  ;; the base, so the derive replays them at the base's version, as it did
  ;; before the column existed.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile  (th/create-profile* 1 {:is-active true})
          proj-id  (:default-project-id profile)
          main     (th/create-file* 1 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          ordinary (th/create-file* 2 {:profile-id (:id profile)
                                       :project-id proj-id
                                       :is-shared false})
          upgraded (conj fmg/available-migrations test-migration)
          c1       (uuid/random)
          c2       (uuid/random)
          old-op   {:type :add-color
                    :color {:id c1 :name "One" :path "Brand/One" :color "#111111" :opacity 1}}
          new-op   {:type :add-color
                    :color {:id c2 :name "Two" :path "Brand / Two" :color "#222222" :opacity 1}}
          branch-file-id (:branch-file-id (create-branch* profile (:id main) "unstamped"))]

      (apply-change* profile branch-file-id old-op)
      (apply-change* profile (:id ordinary) old-op)
      (th/db-update! :file-branch-change {:data-version nil} {:file-id branch-file-id})

      (with-redefs [fmg/available-migrations upgraded]
        (apply-change* profile branch-file-id new-op)
        (apply-change* profile (:id ordinary) new-op)

        (t/is (= [nil test-migration]
                 (->> (oplog-rows branch-file-id)
                      (sort-by :revn)
                      (mapv :data-version))))
        (t/is (= (colors-of (read-file profile (:id ordinary)))
                 (colors-of (read-file profile branch-file-id))))))))

(t/deftest update-from-main-conflict-sides-name-the-documents
  ;; `update-branch-from-main` reports its conflicts in document terms:
  ;; `:main` carries main's value and `:branch` the branch's, whatever the
  ;; direction the engine ran in. Resolving the conflict to `:branch` then
  ;; keeps the branch's value end to end.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile     (th/create-profile* 1 {:is-active true})
          proj-id     (:default-project-id profile)
          branch-fill "#bb0000"
          main-fill   "#00bb00"
          fills       (fn [color] [{:fill-color color :fill-opacity 1}])
          file        (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id proj-id
                                          :is-shared false})
          main-id     (:id file)
          page-id     (-> (th/command! {::th/type :get-file
                                        ::rpc/profile-id (:id profile)
                                        :id main-id})
                          :result :data :pages first)
          shape-id    (uuid/random)
          color-id    (uuid/random)]

      ;; the shape enters the merge base before the fork
      (apply-change* profile main-id
                     {:type :add-obj
                      :page-id page-id
                      :id shape-id
                      :parent-id uuid/zero
                      :frame-id uuid/zero
                      :obj (-> (cts/setup-shape
                                {:id shape-id :name "Coded" :type :rect
                                 :parent-id uuid/zero :frame-id uuid/zero})
                               (assoc :fills (fills "#000000")))})

      (let [create         (create-branch* profile main-id "update-conflict-sides")
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)
            read-fill      (fn []
                             (get-in (th/command! {::th/type :get-file
                                                   ::rpc/profile-id (:id profile)
                                                   :id branch-file-id})
                                     [:result :data :pages-index page-id :objects shape-id
                                      :fills 0 :fill-color]))]

        ;; the same attribute diverges on the two sides
        (apply-change* profile branch-file-id
                       {:type :mod-obj
                        :page-id page-id
                        :id shape-id
                        :operations [{:type :set :attr :fills :val (fills branch-fill)}]})
        (apply-change* profile main-id
                       {:type :mod-obj
                        :page-id page-id
                        :id shape-id
                        :operations [{:type :set :attr :fills :val (fills main-fill)}]})
        ;; main also gains a change the branch never touched, so the update
        ;; has something of main's to apply and takes the write path
        (apply-change* profile main-id
                       {:type :add-color
                        :color {:id color-id :name "Main-only"
                                :color "#336699" :opacity 1}})

        (t/testing "the conflict names the two documents"
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})
                c   (first (-> out :result :conflicts))]
            (t/is (= :conflicts (-> out :result :status)))
            (t/is (= shape-id (:id c)))
            (t/is (= main-fill (get-in c [:main :fills 0 :fill-color])))
            (t/is (= branch-fill (get-in c [:branch :fills 0 :fill-color])))
            (t/is (= {:main (fills main-fill) :branch (fills branch-fill)}
                     (get-in c [:changed-attrs :fills])))))

        (t/testing "resolved to :branch, the branch keeps its own value"
          (let [out (th/command! {::th/type :update-branch-from-main
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id
                                  :resolutions {shape-id :branch}})]
            (t/is (nil? (:error out)))
            (t/is (= :updated (-> out :result :status))))
          (t/is (= branch-fill (read-fill))))))))

;; --- media fix-up on branch saves

(defn- foreign-media-setup
  "The media fix-up scenario: a save into the branch adds an image shape
  referencing a `file_media_object` row ANOTHER file owns, the case the
  server-side media fix-up handles on an ordinary save. Returns the
  pieces the assertions need."
  []
  (let [profile  (th/create-profile* 1 {:is-active true})
        proj-id  (:default-project-id profile)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id proj-id
                                     :is-shared false})
        foreign  (th/create-file* 2 {:profile-id (:id profile)
                                     :project-id proj-id
                                     :is-shared false})
        storage  (-> (:app.storage/storage th/*system*)
                     (assoc :app.storage/backend :fs))
        sobj     (sto/put-object! storage {::sto/content (sto/content "foreign-image")
                                           :bucket :file-media-object
                                           :content-type "image/png"})
        fmo      (th/create-file-media-object* {:file-id (:id foreign)
                                                :name "foreign.png"
                                                :mtype "image/png"
                                                :media-id (:id sobj)})
        create   (create-branch* profile (:id file) "media-fixup")
        branch-file-id (:branch-file-id create)
        page-id  (-> (read-file profile branch-file-id) :data :pages first)
        shape-id (uuid/random)]

    (apply-change* profile branch-file-id
                   {:type :add-obj
                    :page-id page-id
                    :id shape-id
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :obj (cts/setup-shape
                          {:id shape-id :name "Image" :type :image
                           :parent-id uuid/zero :frame-id uuid/zero
                           :metadata {:id (:id fmo)
                                      :width 100 :height 100
                                      :mtype "image/png"}})})

    {:profile profile
     :main-id (:id file)
     :branch-id (:id create)
     :branch-file-id branch-file-id
     :page-id page-id
     :shape-id shape-id
     :fmo-id (:id fmo)}))

(defn- media-ref
  "The media row id a shape references: image shapes carry it in
  `:metadata`, fill images in their `:fills`."
  [obj]
  (or (get-in obj [:metadata :id])
      (get-in obj [:fills 0 :fill-image :id])))

(defn- derived-obj
  [profile file-id page-id shape-id]
  (-> (read-file profile file-id)
      :data :pages-index (get page-id) :objects (get shape-id)))

(t/deftest branch-save-fixes-foreign-media-refs-in-the-op-log
  ;; An ordinary save carries the media fix-up with its payload: the
  ;; foreign row is copied into the saving file and the reference is
  ;; rewritten. A branch stores no payload — the change vector IS the
  ;; state — so the rewrite has to land in the change vector before the
  ;; op is appended, or the derive reproduces the foreign reference and
  ;; the copied row is left unreferenced.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile branch-file-id page-id shape-id fmo-id]}
          (foreign-media-setup)

          stored-ref
          (-> (oplog-rows branch-file-id)
              first :changes blob/decode first :obj media-ref)

          derived-ref
          (-> (derived-obj profile branch-file-id page-id shape-id)
              media-ref)]

      (t/testing "the op log records the rewritten reference"
        (t/is (= 1 (count (oplog-rows branch-file-id))))
        (t/is (uuid? stored-ref))
        (t/is (not= fmo-id stored-ref)))

      (t/testing "the derive reproduces the reference the op records"
        (t/is (= stored-ref derived-ref)))

      (t/testing "the derived branch references a media row it owns"
        (let [row (th/db-get :file-media-object {:id derived-ref})]
          (t/is (some? row))
          (t/is (= branch-file-id (:file-id row)))))

      (t/testing "the copied row is the referenced one, not an orphan"
        (let [rows (th/db-query :file-media-object {:file-id branch-file-id})]
          (t/is (= 1 (count rows)))
          (t/is (= derived-ref (:id (first rows)))))))))

(t/deftest merge-carries-the-fixed-media-into-main
  ;; The fix-up's copy is owned by the branch file, so the merge must
  ;; carry the row into main and point main's shape at a row main owns.
  ;; With the reference left foreign, `files_branch.clj::unpaired-media-rows`
  ;; drops the row (it filters by branch ownership) and main ends up
  ;; referencing media it does not own.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile main-id branch-id branch-file-id page-id shape-id]}
          (foreign-media-setup)

          out
          (th/command! {::th/type :merge-file-branch
                        ::rpc/profile-id (:id profile)
                        :branch-id branch-id
                        :keep-branch true})]

      (t/is (nil? (:error out)))
      (t/is (= :merged (-> out :result :status)))

      (let [ref (-> (derived-obj profile main-id page-id shape-id) media-ref)
            row (th/db-get :file-media-object {:id ref})]
        (t/is (some? row))
        (t/is (= main-id (:file-id row))))

      ;; the branch keeps deriving its own state from the kept op log
      (let [ref (-> (derived-obj profile branch-file-id page-id shape-id) media-ref)
            row (th/db-get :file-media-object {:id ref})]
        (t/is (some? row))
        (t/is (= branch-file-id (:file-id row)))))))
