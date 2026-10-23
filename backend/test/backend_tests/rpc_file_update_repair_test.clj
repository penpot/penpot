;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-update-repair-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
   [app.metrics :as mtx]
   [app.msgbus :as mbus]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [app.srepl.procs.file-repair :as file-repair]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   io.prometheus.client.CollectorRegistry))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- with-repair-flags
  [f]
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (f)))

(defn- with-full-repair-flags
  [f]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (f)))

(defn- repair-count
  [outcome]
  (or (.getSampleValue ^CollectorRegistry
       (mtx/get-registry (::mtx/metrics th/*system*))
                       "penpot_rpc_update_file_repair_total"
                       (into-array String ["outcome"])
                       (into-array String [(name outcome)]))
      0.0))

(defn- add-rect
  [page-id shape-id]
  {:type :add-obj
   :page-id page-id
   :id shape-id
   :parent-id uuid/zero
   :frame-id uuid/zero
   :components-v2 true
   :obj (cts/setup-shape
         {:id shape-id
          :name "rect"
          :frame-id uuid/zero
          :parent-id uuid/zero
          :type :rect})})

(defn- set-attr
  [page-id shape-id attr val]
  {:type :mod-obj
   :id shape-id
   :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn- update-file
  [profile-id file-id revn changes & {:keys [skip-validate session-id]
                                      :or {session-id (uuid/random)}}]
  (th/command! (cond-> {::th/type :update-file
                        ::rpc/profile-id profile-id
                        :id file-id
                        :session-id session-id
                        :revn revn
                        :vern 0
                        :features cfeat/supported-features
                        :changes changes}
                 skip-validate (assoc :skip-validate true))))

(defn- stored-file
  [file-id]
  (bfc/get-file th/*system* file-id :realize? true))

(defn- change-rows
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter (fn [row]
                 (and (some? (:changes row))
                      (nil? (:label row)))))
       (sort-by :created-at)
       (vec)))

(defn- label-rows
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter :label)
       (vec)))

(defn- pointer-map-in?
  [x]
  (cond
    (pmap/pointer-map? x) true
    (map? x) (boolean (some pointer-map-in? (vals x)))
    (sequential? x) (boolean (some pointer-map-in? x))
    :else false))

(defn- setup-file
  [& {:keys [is-shared] :or {is-shared false}}]
  (let [profile  (th/create-profile* 1 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared is-shared})
        page-id  (first (get-in file [:data :pages]))
        shape-a  (uuid/next)
        shape-b  (uuid/next)
        out      (update-file (:id profile) (:id file) 0
                              [(add-rect page-id shape-a)
                               (add-rect page-id shape-b)])]
    (t/is (th/success? out))
    {:profile profile
     :file-id (:id file)
     :page-id page-id
     :shape-a shape-a
     :shape-b shape-b
     :revn (:revn (stored-file (:id file)))}))

(defn- break-frame
  [{:keys [profile file-id page-id shape-a revn]}]
  (let [out (update-file (:id profile) file-id revn
                         [(set-attr page-id shape-a :frame-id (uuid/next))]
                         :skip-validate true)]
    (t/is (th/success? out))
    (stored-file file-id)))

(t/deftest repairs-preexisting-frame-not-found
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            before (repair-count :repaired)
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")])
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-id :objects shape-a])
            frame  (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])]
        (t/is (th/success? out))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (some? frame))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= 1.0 (- (repair-count :repaired) before)))))))

(t/deftest repairs-preexisting-frame-not-found-with-schema-validation
  (with-full-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken           (break-frame ctx)
            n                (:revn broken)
            before-repaired  (repair-count :repaired)
            before-unrepaired (repair-count :unrepaired)
            out              (update-file (:id profile) file-id n
                                          [(set-attr page-id shape-b :name "renamed")])
            stored           (stored-file file-id)
            shape            (get-in stored [:data :pages-index page-id :objects shape-a])
            frame            (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])
            labels           (label-rows file-id)
            repair           (filter #(= (str "internal/repair/" n) (:label %)) labels)
            snap             (filter #(= (str "internal/snapshot/" n) (:label %)) labels)]
        (t/is (th/success? out))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (some? frame))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= (+ n 2) (:revn stored)))
        (t/is (= 1 (count repair)))
        (t/is (zero? (count snap)))
        (t/is (= 1.0 (- (repair-count :repaired) before-repaired)))
        (t/is (= 0.0 (- (repair-count :unrepaired) before-unrepaired)))))))

(t/deftest rejects-client-caused-break
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a revn]} (setup-file)
            before (stored-file file-id)
            out    (update-file (:id profile) file-id revn
                                [(set-attr page-id shape-a :frame-id (uuid/next))])
            after  (stored-file file-id)]
        (t/is (not (th/success? out)))
        (t/is (= :referential-integrity (:code (ex-data (:error out)))))
        (t/is (= (:revn before) (:revn after)))))))

(t/deftest skip-validate-does-not-repair
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")]
                                :skip-validate true)
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-id :objects shape-a])]
        (t/is (th/success? out))
        (t/is (nil? (get-in stored [:data :pages-index page-id :objects (:frame-id shape)])))))))

(t/deftest damage-on-untouched-page-is-left
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a revn]} (setup-file)
            page-2 (uuid/next)
            shape-c (uuid/next)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id revn
                                       [{:type :add-page :id page-2 :name "page-2"}
                                        (add-rect page-2 shape-c)])))
            file   (stored-file file-id)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id (:revn file)
                                       [(set-attr page-2 shape-c :frame-id (uuid/next))]
                                       :skip-validate true)))
            file   (stored-file file-id)
            out    (update-file (:id profile) file-id (:revn file)
                                [(set-attr page-id shape-a :name "on-page-1")])
            stored (stored-file file-id)
            shape  (get-in stored [:data :pages-index page-2 :objects shape-c])]
        (t/is (th/success? out))
        (t/is (nil? (get-in stored [:data :pages-index page-2 :objects (:frame-id shape)])))))))

(t/deftest growing-scope-repairs-component-touched-by-repair
  (thi/reset-idmap!)
  (with-repair-flags
    (fn []
      (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                        (assoc :revn 0)
                        (tho/add-simple-component :component1 :root1 :child1)
                        (ths/add-sample-shape :other)
                        (update-in [:data :components (thi/id :component1)]
                                   assoc :main-instance-id (uuid/next)
                                   :objects {(uuid/next) {:id (uuid/next)}}))
            page-id (thi/id :page1)
            changes [(set-attr page-id (thi/id :other) :name "renamed")]
            cfg     {::files-update/validation-timings (atom {})}
            result  (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                      (@#'files-update/process-changes-and-validate cfg file changes nil))
            stored  result]
        (t/is (some? (::files-update/repair stored)))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (nil? (get-in stored [:data :components (thi/id :component1) :objects])))))))

(t/deftest repair-records-timings-with-schema-validation
  (thi/reset-idmap!)
  (with-full-repair-flags
    (fn []
      (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                        (assoc :revn 0)
                        (ths/add-sample-shape :shape1)
                        (ths/update-shape :shape1 :frame-id (uuid/next)))
            changes [(set-attr (thi/id :page1) (thi/id :shape1) :name "renamed")]
            timings (atom {})
            cfg     {::files-update/validation-timings timings}
            result  (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                      (@#'files-update/process-changes-and-validate cfg file changes nil))]
        (t/is (some? (::files-update/repair result)))
        (t/is (number? (:repair-ms @timings)))
        (t/is (number? (:reprocess-changes-ms @timings)))
        (t/is (number? (:validate-schema-ms @timings)))))))

(t/deftest unrepairable-rejects-and-calls-repair-once
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            broken (break-frame ctx)
            calls  (atom 0)
            before (repair-count :unrepaired)
            out    (with-redefs [cfr/repair-file (fn [_ _ _]
                                                   (swap! calls inc)
                                                   [])]
                     (update-file (:id profile) file-id (:revn broken)
                                  [(set-attr page-id shape-b :name "renamed")]))]
        (t/is (not (th/success? out)))
        (t/is (= :referential-integrity (:code (ex-data (:error out)))))
        (t/is (= 1 @calls))
        (t/is (= 1.0 (- (repair-count :unrepaired) before)))))))

(t/deftest repair-writes-change-rows-and-reuses-snapshot
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
            broken  (break-frame ctx)
            before  (count (change-rows file-id))
            session (uuid/next)
            calls   (atom [])
            n       (:revn broken)
            out     (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                              (swap! calls conj {:topic topic :message message}))]
                      (update-file (:id profile) file-id n
                                   [(set-attr page-id shape-b :name "renamed")]
                                   :session-id session))
            rows    (change-rows file-id)
            new     (subvec rows before)
            labels  (label-rows file-id)
            repair  (filter #(= (str "internal/repair/" n) (:label %)) labels)
            snap    (filter #(= (str "internal/snapshot/" n) (:label %)) labels)
            msgs    (filter #(= :file-change (:type (:message %))) @calls)
            stored  (stored-file file-id)]
        (t/is (th/success? out))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))
        (t/is (= 2 (count new)))
        (t/is (= (inc n) (:revn (first new))))
        (t/is (= files-update/repair-session-id (:session-id (first new))))
        (t/is (some? (:deleted-at (first new))))
        (t/is (= (:created-at (first new)) (:updated-at (first new))))
        (t/is (= (+ n 2) (:revn (second new))))
        (t/is (= session (:session-id (second new))))
        (t/is (some? (:deleted-at (second new))))
        (t/is (= (:created-at (second new)) (:updated-at (second new))))
        (t/is (not (pos? (compare (:created-at (first new))
                                  (:created-at (second new))))))
        (t/is (= 1 (count repair)))
        (t/is (zero? (count snap)))
        (t/is (>= (count msgs) 2))
        (t/is (true? (get-in (first msgs) [:message :repaired])))
        (t/is (= files-update/repair-session-id
                 (get-in (first msgs) [:message :session-id])))
        (t/is (= session (get-in (second msgs) [:message :session-id])))
        (t/is (not (true? (get-in (second msgs) [:message :repaired]))))
        (t/is (= (+ n 2) (get-in (second msgs) [:message :revn])))))))

(t/deftest repair-library-change-keeps-saver-session
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file :is-shared true)
            broken  (break-frame ctx)
            session (uuid/next)
            calls   (atom [])
            out     (with-redefs [mbus/pub! (fn [_cfg & {:keys [topic message]}]
                                              (swap! calls conj {:topic topic :message message}))]
                      (update-file (:id profile) file-id (:revn broken)
                                   [(set-attr page-id shape-b :name "renamed")]
                                   :session-id session))
            lib-msgs (filter #(= :library-change (:type (:message %))) @calls)]
        (t/is (th/success? out))
        (t/is (seq lib-msgs))
        (doseq [msg lib-msgs]
          (t/is (= session (get-in msg [:message :session-id])))
          (t/is (not= files-update/repair-session-id
                      (get-in msg [:message :session-id]))))))))

(t/deftest repair-snapshot-without-auto-snapshot
  (with-redefs [cf/flags (-> cf/flags
                             (conj :file-validation)
                             (disj :auto-file-snapshot))]
    (let [{:keys [profile file-id page-id shape-b] :as ctx} (setup-file)
          broken (break-frame ctx)
          n      (:revn broken)
          out    (update-file (:id profile) file-id n
                              [(set-attr page-id shape-b :name "renamed")])
          labels (label-rows file-id)
          repair (filter #(= (str "internal/repair/" n) (:label %)) labels)
          snaps  (filter #(re-find #"^internal/snapshot/" (str (:label %))) labels)
          row    (first repair)
          snap   (fsnap/get-snapshot th/*system* file-id (:id row))
          sfile  {:id file-id
                  :features (:features (stored-file file-id))
                  :data (:data snap)}]
      (t/is (th/success? out))
      (t/is (= 1 (count repair)))
      (t/is (zero? (count snaps)))
      (t/is (false? (pointer-map-in? (:data snap))))
      (t/is (some? (cfv/validate-file sfile {}))))))

(t/deftest srepl-repair-grows-revn-by-iterations
  (thi/reset-idmap!)
  (let [file    (-> (thf/sample-file :file1 :page-label :page1)
                    (assoc :revn 3)
                    (ths/add-sample-shape :shape1)
                    (ths/update-shape :shape1 :frame-id (uuid/next)))
        loop    (cfr/repair-file-until-valid
                 file {}
                 (fn [file _] (cfv/validate-file file {})))
        wrapped (file-repair/repair-file file {})]
    (t/is (pos? (:iterations loop)))
    (t/is (= (+ (:revn file) (:iterations loop)) (:revn wrapped)))
    (t/is (nil? (cfv/validate-file wrapped {})))))

(t/deftest healthy-save-writes-one-change-row
  (with-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-b revn]} (setup-file)
            before (count (change-rows file-id))
            out    (update-file (:id profile) file-id revn
                                [(set-attr page-id shape-b :name "ok")])
            labels (label-rows file-id)]
        (t/is (th/success? out))
        (t/is (= 1 (- (count (change-rows file-id)) before)))
        (t/is (empty? (filter #(re-find #"^internal/repair/" (str (:label %))) labels)))))))

(t/deftest repairs-several-missing-children-on-root
  (with-full-repair-flags
    (fn []
      (let [{:keys [profile file-id page-id shape-a shape-b revn]} (setup-file)
            r1     (uuid/next)
            r2     (uuid/next)
            r3     (uuid/next)
            _      (t/is (th/success?
                          (update-file (:id profile) file-id revn
                                       [(set-attr page-id uuid/zero :shapes
                                                  [shape-a shape-b r1 r2 r3])]
                                       :skip-validate true)))
            broken (stored-file file-id)
            errors (cfv/validate-file broken {})
            out    (update-file (:id profile) file-id (:revn broken)
                                [(set-attr page-id shape-b :name "renamed")])
            stored (stored-file file-id)
            root   (get-in stored [:data :pages-index page-id :objects uuid/zero])]
        (t/is (= [:child-not-found :child-not-found :child-not-found]
                 (mapv :code errors)))
        (t/is (= #{uuid/zero} (into #{} (map :shape-id) errors)))
        (t/is (th/success? out))
        (t/is (= [shape-a shape-b] (:shapes root)))
        (t/is (nil? (cfv/validate-file stored {})))
        (t/is (= "renamed"
                 (get-in stored [:data :pages-index page-id :objects shape-b :name])))))))
