;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-pointer-base-test
  "A branch base is a snapshot of the source file that shares the source
  file's fragments (fdata/pointer-map). Fragment rows are keyed by
  `(file_id, id)`, so the base readers must realize it with the source
  file id, and the source file's file-gc must keep every fragment a live
  base names."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.branch-merge :as bm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-branch :as fbranch]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [clojure.walk :as walk]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- pointer-map-enabled?
  []
  (contains? (cfeat/get-enabled-features cf/flags) "fdata/pointer-map"))

(defn- update-file!
  [profile-id file-id changes]
  (let [row (th/db-get :file {:id file-id})
        out (th/command! {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn (:revn row)
                          :vern (:vern row)
                          :features cfeat/supported-features
                          :changes changes})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- add-rect
  [page-id]
  (let [id (uuid/random)]
    {:type :add-obj
     :page-id page-id
     :id id
     :parent-id uuid/zero
     :frame-id uuid/zero
     :components-v2 true
     :obj (cts/setup-shape {:id id
                            :name "rect"
                            :frame-id uuid/zero
                            :parent-id uuid/zero
                            :type :rect})}))

(defn- create-branch!
  [profile-id file-id name]
  (let [out (th/command! {::th/type :create-file-branch
                          ::rpc/profile-id profile-id
                          :file-id file-id
                          :name name})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- live-fragment-ids
  [file-id]
  (->> (th/db-query :file-data {:file-id file-id :type "fragment"})
       (filter #(nil? (:deleted-at %)))
       (into #{} (map :id))))

(defn- page-pointer-ids
  "{page-id -> fragment id} of decoded file data."
  [data]
  (into {}
        (keep (fn [[id page]]
                (when (pmap/pointer-map? page)
                  [id (pmap/get-id page)])))
        (:pages-index data)))

(defn- base-snapshot
  [branch-id]
  (let [row (th/db-get :file-branch {:id branch-id})]
    (fsnap/get-snapshot th/*system* (:source-file-id row) (:base-snapshot-id row))))

(defn- holds-pointers?
  [data]
  (let [found (volatile! false)]
    (walk/prewalk (fn [x]
                    (when (pmap/pointer-map? x) (vreset! found true))
                    (if (pmap/pointer-map? x) {} x))
                  data)
    @found))

(defn- realized-data
  [file-id]
  (:data (bfc/get-file th/*system* file-id :realize? true)))

(defn- setup
  []
  (t/is (pointer-map-enabled?) "these tests need fdata/pointer-map")
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile) :file-id (:id file)}))

(t/deftest create-stores-the-base-as-source-pointers
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          before (live-fragment-ids file-id)
          branch (create-branch! profile-id file-id "pointers")
          base   (base-snapshot (:id branch))
          ids    (page-pointer-ids (:data base))]
      ;; every page of the base is a pointer into the source file's own
      ;; fragment rows, and creating the branch wrote no fragment
      (t/is (seq ids))
      (t/is (= (count ids) (count (:pages-index (:data base)))))
      (t/is (every? before (vals ids)))
      (t/is (= before (live-fragment-ids file-id))))))

(t/deftest base-readers-realize-with-the-source-file-id
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          branch (create-branch! profile-id file-id "readers")
          ;; whatever creation stores, pin a base that holds the source
          ;; file's pointers: the readers are what this test is about
          source (bfc/get-file th/*system* file-id)
          _      (t/is (seq (page-pointer-ids (:data source))))
          snap   (fsnap/create! th/*system* source
                                {:label "branch-base/readers"
                                 :profile-id profile-id
                                 :created-by "system"})
          _      (th/db-update! :file-branch
                                {:base-snapshot-id (:id snap)}
                                {:id (:id branch)})
          main   (realized-data file-id)]

      (t/testing "the derive reads the base"
        (let [file (bfc/get-file th/*system* (:branch-file-id branch)
                                 :realize? true :include-base-data? true)]
          (t/is (not (holds-pointers? (:data file))))
          (t/is (= (:pages-index main) (:pages-index (:data file))))
          (t/is (not (holds-pointers? (::bfc/base-data file))))
          (t/is (= (:pages-index main) (:pages-index (::bfc/base-data file))))))

      (t/testing "the snapshot reader reads the base"
        (let [row  (th/db-get :file-branch {:id (:id branch)})
              data (db/run! th/*system* #(#'fbranch/get-base-data % row))]
          (t/is (not (holds-pointers? data)))
          (t/is (= (:pages-index main) (:pages-index data))))))))

(t/deftest file-gc-keeps-the-fragments-a-base-names
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          branch  (create-branch! profile-id file-id "gc")
          page-id (first (:pages (realized-data file-id)))
          pinned  (set (vals (page-pointer-ids (:data (base-snapshot (:id branch))))))]
      (t/is (seq pinned))

      ;; the edit replaces the page's fragment on main, so only the
      ;; base still names the old one
      (update-file! profile-id file-id [(add-rect page-id)])
      (let [main-ids (set (vals (page-pointer-ids
                                 (:data (bfc/get-file th/*system* file-id)))))]
        (t/is (seq (remove main-ids pinned))))
      ;; the save took an automatic snapshot of pre-edit main, which names
      ;; the same fragments; drop every snapshot but the base so the base
      ;; is what keeps them alive
      (let [base-id (:base-snapshot-id (th/db-get :file-branch {:id (:id branch)}))]
        (th/db-exec! ["delete from file_data where file_id = ? and type = 'snapshot' and id <> ?"
                      file-id base-id])
        (th/db-exec! ["delete from file_change where file_id = ? and label is not null and id <> ?"
                      file-id base-id]))

      (t/is (true? (th/run-task! :file-gc {:file-id file-id})))
      (t/is (every? (live-fragment-ids file-id) pinned))

      (let [diff (:result (th/command! {::th/type :get-branch-diff
                                        ::rpc/profile-id profile-id
                                        :branch-id (:id branch)
                                        :direction :main->branch}))]
        (t/is (= 1 (-> diff :stats :added)))))))

(t/deftest update-from-main-pins-main-pointers
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          branch  (create-branch! profile-id file-id "update")
          page-id (first (:pages (realized-data file-id)))]
      (update-file! profile-id file-id [(add-rect page-id)])
      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id profile-id
                              :branch-id (:id branch)})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      ;; the repositioned base names main's current fragments for every
      ;; page the update left equal to main
      (let [main-ids (page-pointer-ids (:data (bfc/get-file th/*system* file-id)))
            base-ids (page-pointer-ids (:data (base-snapshot (:id branch))))]
        (t/is (seq base-ids))
        (t/is (= main-ids base-ids)))

      (t/is (= (:pages-index (realized-data file-id))
               (:pages-index (realized-data (:branch-file-id branch))))))))

(t/deftest merge-keeps-the-fragments-of-untouched-pages
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          page-a  (first (:pages (realized-data file-id)))
          page-b  (uuid/random)
          _       (update-file! profile-id file-id
                                [{:type :add-page :id page-b :name "B"}])
          branch  (create-branch! profile-id file-id "merge")
          before  (page-pointer-ids (:data (bfc/get-file th/*system* file-id)))]
      (update-file! profile-id (:branch-file-id branch) [(add-rect page-a)])
      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id profile-id
                              :branch-id (:id branch)})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (let [after (page-pointer-ids (:data (bfc/get-file th/*system* file-id)))]
        ;; the merged page is new, the untouched one keeps its fragment
        (t/is (not= (get before page-a) (get after page-a)))
        (t/is (= (get before page-b) (get after page-b)))))))

(defn- rename-shape
  [page-id shape-id name]
  {:type :mod-obj
   :page-id page-id
   :id shape-id
   :operations [{:type :set :attr :name :val name}]})

(defn- eager-compare
  "The comparison with every page of every side realized and no bound."
  [file-id branch-file-id dir]
  (db/run! th/*system*
           (fn [cfg]
             (let [main   (:data (bfc/get-file cfg file-id :realize? true))
                   bfile  (bfc/get-file cfg branch-file-id :realize? true :include-base-data? true)
                   base   (#'fbranch/branch-base-data cfg file-id bfile)
                   branch (bm/remap-refs (:data bfile)
                                         (#'fbranch/branch-id-map cfg branch-file-id file-id))]
               (bm/compute-merge base main branch dir)))))

(t/deftest compare-loads-only-the-pages-main-moved
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          page-a  (first (:pages (realized-data file-id)))
          others  (vec (repeatedly 5 uuid/random))
          rect    (add-rect page-a)
          shape   (:id rect)
          _       (update-file! profile-id file-id
                                (into [rect]
                                      (map (fn [id] {:type :add-page :id id :name (str id)}))
                                      others))
          branch  (create-branch! profile-id file-id "lazy")
          bfid    (:branch-file-id branch)
          page-b  (first others)]
      ;; the branch renames the shape main renames too, a conflict on
      ;; page A, and adds a shape; main also adds a shape on page B, so
      ;; main moved two of six pages
      (update-file! profile-id bfid [(rename-shape page-a shape "branch") (add-rect page-a)])
      (update-file! profile-id file-id [(rename-shape page-a shape "main") (add-rect page-b)])

      (doseq [dir [:branch->main :main->branch]]
        (let [loads (atom 0)
              load  fdata/load-pointer
              out   (with-redefs [fdata/load-pointer (fn [& args] (swap! loads inc) (apply load args))]
                      (th/command! {::th/type :get-branch-diff
                                    ::rpc/profile-id profile-id
                                    :branch-id (:id branch)
                                    :direction dir}))
              eager (eager-compare file-id bfid dir)]
          (t/is (nil? (:error out)))
          (t/testing (str dir " gives the summary of the whole-file comparison")
            (t/is (= (:stats eager) (:stats (:result out))))
            (t/is (= (:changes eager) (:changes (:result out))))
            (t/is (= (:conflicts eager) (:conflicts (:result out))))
            (t/is (= 1 (count (:conflicts eager)))))
          (t/testing (str dir " loads the base's six pages and main's two moved ones")
            (t/is (= 8 @loads))))))))

(t/deftest compare-reports-a-page-reorder-on-main
  ;; the page order lives in the document root, not in a page's fragment,
  ;; so a reorder moves no fragment and must not bound the comparison
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [{:keys [profile-id file-id]} (setup)
          page-b (uuid/random)
          _      (update-file! profile-id file-id [{:type :add-page :id page-b :name "B"}])
          branch (create-branch! profile-id file-id "reorder")]
      (update-file! profile-id file-id [{:type :mov-page :id page-b :index 0}])
      (let [out   (th/command! {::th/type :get-branch-diff
                                ::rpc/profile-id profile-id
                                :branch-id (:id branch)
                                :direction :main->branch})
            eager (eager-compare file-id (:branch-file-id branch) :main->branch)]
        (t/is (nil? (:error out)))
        (t/is (seq (:changes eager)))
        (t/is (= (:changes eager) (:changes (:result out))))))))
