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
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
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
        (let [rows (stored-data-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (empty? (-> rows first :data blob/decode :pages)))))

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
        (let [rows (stored-data-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (empty? (-> rows first :data blob/decode :pages)))))

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
  ;; hand it. In an update from main the engine runs with the branch as
  ;; `theirs` and main as `ours` (`branch_merge.cljc::compute-merge*`), so
  ;; `branch_merge.cljc::three-way-entities` writes the branch's value under
  ;; the key named `:main` and main's value under the key named `:branch`.
  ;; The client re-keys the conflicts it displays and sends resolutions in
  ;; document terms; the command inverts them before applying
  ;; (`files_branch.clj::update-branch-from-main`). This test pins that
  ;; contract at the command boundary: `:main` brings main's value into the
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
          (t/is (= 1 (count (stored-data-rows branch-file-id)))))))))
