;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.files-update-refusal-test
  "Counters that update-file records for refused saves, soft validation
  failures, and lagged saves, and the bounded labels they carry."
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.validate :as val]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- metrics
  []
  (ig/init-key :app.metrics/metrics {:default main/default-metrics}))

(defn- counter
  "Value of a counter series, or 0 when the series does not exist yet."
  [metrics name label-names label-values]
  (or (.getSampleValue ^CollectorRegistry (mtx/get-registry metrics)
                       ^String name
                       (into-array String label-names)
                       (into-array String label-values))
      0.0))

(defn- refused
  "Value of the refused-saves series of the system's metrics with the
  labels `stage`, `code`, and `outcome`."
  [stage code outcome]
  (counter (:app.metrics/metrics th/*system*)
           "penpot_rpc_update_file_refused_total"
           ["stage" "code" "outcome"]
           [stage code outcome]))

(defn- rejected-commits
  "Value of the rejected-commits series of the system's metrics with the
  label `code`."
  [code]
  (counter (:app.metrics/metrics th/*system*)
           "penpot_rpc_update_file_rejected_commits_total"
           ["code"] [code]))

(defn- unrepaired
  []
  (counter (:app.metrics/metrics th/*system*)
           "penpot_rpc_update_file_repair_total"
           ["outcome"] ["unrepaired"]))

(defn- validation-cfg
  [metrics]
  {::mtx/metrics metrics
   ::files-update/validation-timings (atom {})
   ::files-update/incoming-revn 0
   ::files-update/stored-revn 0})

(defn- setup-file
  "A stored file with one empty page, and the profile that owns it."
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    {:profile-id (:id profile)
     :file       file
     :page-id    (first (:pages (:data file)))}))

(defn- save!
  "Calls update-file on the file of `ctx` with `params` over the defaults."
  [{:keys [profile-id file]} params]
  (th/command! (merge {::th/type :update-file
                       ::rpc/profile-id profile-id
                       :id (:id file)
                       :session-id (uuid/next)
                       :revn 0
                       :vern 0
                       :features (cfeat/get-enabled-features cf/flags)}
                      params)))

(defn- broken-schema-file
  "A file whose only defect is a shape with an invalid `:type`."
  []
  (let [page-id (uuid/next)
        shape   (-> (cts/setup-shape {:id (uuid/next)
                                      :type :rect
                                      :name "rect"
                                      :x 0 :y 0 :width 10 :height 10
                                      :parent-id uuid/zero
                                      :frame-id uuid/zero})
                    (assoc :type :not-a-shape))]
    {:id (uuid/next)
     :revn 0
     :version 0
     :data {:pages [page-id]
            :pages-index {page-id {:id page-id
                                   :name "Page 1"
                                   :objects {(:id shape) shape}}}}}))

(defn- broken-referential-file
  "A file whose root lists a child that does not exist."
  []
  (let [page-id (uuid/next)
        root    (-> (cts/setup-shape {:id uuid/zero
                                      :type :frame
                                      :name "Root"
                                      :x 0 :y 0 :width 100 :height 100
                                      :parent-id uuid/zero
                                      :frame-id uuid/zero})
                    (update :shapes (fnil conj []) (uuid/next)))]
    {:id (uuid/next)
     :revn 0
     :version 0
     :features #{"components/v2"}
     :data {:pages [page-id]
            :pages-index {page-id {:id page-id
                                   :name "Page 1"
                                   :objects {uuid/zero root}}}}}))

(defn- touch-first-page
  "A change that touches the first page of `file` and leaves it as it
  was: the schema check covers only the pages a save touches."
  [file]
  [{:type :mod-obj
    :page-id (first (:pages (:data file)))
    :id uuid/zero
    :operations []}])

(defn- set-attr
  [page-id shape-id attr val]
  {:type :mod-obj
   :page-id page-id
   :id shape-id
   :operations [{:type :set :attr attr :val val}]})

(defn- commit
  [& changes]
  {:commit-id (uuid/next) :changes (vec changes)})

(defn- invalid-type-commit
  "A commit group whose fold leaves a shape invalid. The operation passes
  the params schema (`:val` is untyped), so the fold itself refuses it."
  [page-id]
  (commit (set-attr page-id uuid/zero :type :not-a-shape)))

(defn- page-name-commit
  "A commit group that applies and validates."
  [page-id name]
  (commit {:type :mod-page :id page-id :name name}))

(defn- debug-fault
  "A change that asks the backend to fail with `fault` while applied."
  [fault]
  {:type :set-plugin-data
   :object-type :file
   :namespace :penpot-debug
   :key (str "save-fault:" fault)
   :value nil})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest soft-schema-failure-is-counted-with-the-failing-schema-key
  (let [metrics (metrics)
        cfg     (validation-cfg metrics)
        file    (broken-schema-file)
        before  (counter metrics "penpot_rpc_update_file_soft_failure_total"
                         ["check" "reason"] ["schema" "type"])]
    (binding [cf/flags #{:soft-file-schema-validation}
              pmap/*tracked* (pmap/create-tracked)]
      (@#'files-update/process-changes-and-validate
       cfg file (touch-first-page file) nil nil))

    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_soft_failure_total"
                      ["check" "reason"] ["schema" "type"])))))

(t/deftest soft-referential-failure-is-counted-with-the-reported-code
  (let [metrics (metrics)
        cfg     (validation-cfg metrics)
        file    (broken-referential-file)
        page-id (first (:pages (:data file)))
        before  (counter metrics "penpot_rpc_update_file_soft_failure_total"
                         ["check" "reason"] ["affected" "child-not-found"])]
    (binding [cf/flags #{:soft-file-validation}
              pmap/*tracked* (pmap/create-tracked)]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
        (@#'files-update/process-changes-and-validate
         cfg file
         [{:type :mod-obj :page-id page-id :id uuid/zero :operations []}]
         nil nil)))

    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_soft_failure_total"
                      ["check" "reason"] ["affected" "child-not-found"])))))

(t/deftest soft-failure-records-nothing-without-a-metrics-instance
  ;; The bisection retries of `apply-commits` run without a metrics
  ;; instance. A soft failure that raised there would reject a commit
  ;; that is valid.
  (let [cfg  {::files-update/validation-timings (atom {})
              ::files-update/incoming-revn 0
              ::files-update/stored-revn 0}
        file (broken-schema-file)]
    (binding [cf/flags #{:soft-file-schema-validation}
              pmap/*tracked* (pmap/create-tracked)]
      (t/is (map? (@#'files-update/process-changes-and-validate
                   cfg file (touch-first-page file) nil nil))))))

(t/deftest refused-update-file-is-counted-with-stage-and-code
  (let [{:keys [page-id] :as ctx} (setup-file)
        before (refused "changes" "data-validation" "refused")
        out    (save! ctx {:changes [(set-attr page-id uuid/zero :type :not-a-shape)]})]
    (t/is (some? (:error out)))
    (t/is (= (inc before)
             (refused "changes" "data-validation" "refused")))))

(t/deftest referential-refusal-reports-the-gate-stage-and-code
  ;; The damage has to come from the changes. A file damaged before the
  ;; save is repairable, and the save then goes through instead of
  ;; refusing.
  (with-redefs [cf/flags (conj cf/flags :file-validation)]
    (let [{:keys [page-id] :as ctx} (setup-file)
          shape-id (uuid/next)
          _        (t/is (th/success? (save! ctx {:changes [(th/add-rect-change page-id shape-id)]})))
          before   (refused "referential" "referential-integrity" "refused")
          out      (save! ctx {:revn 1
                               :changes [(set-attr page-id shape-id :parent-id (uuid/next))]})]
      (t/is (= :referential-integrity (:code (ex-data (:error out)))))
      (t/is (= (inc before)
               (refused "referential" "referential-integrity" "refused"))))))

(t/deftest failed-repair-reports-the-stage-of-the-first-failure
  ;; The save fails the referential gate on damage the file already had.
  ;; The repair fixes it and then fails its own schema check, which it
  ;; times as `validate-schema`. The save was refused at the gate.
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (let [{:keys [page-id] :as ctx} (setup-file)
          shape-a (uuid/next)
          shape-b (uuid/next)
          _       (t/is (th/success? (save! ctx {:changes [(th/add-rect-change page-id shape-a)
                                                           (th/add-rect-change page-id shape-b)]})))
          _       (t/is (th/success? (save! ctx {:revn 1
                                                 :skip-validate true
                                                 :changes [(set-attr page-id shape-a :frame-id (uuid/next))]})))
          before  (refused "referential" "referential-integrity" "refused")
          before-schema (refused "schema" "referential-integrity" "refused")
          before-unrepaired (unrepaired)
          out     (with-redefs [val/validate-file-schema!
                                (fn [_]
                                  (throw (ex-info "schema" {:type :validation
                                                            :code :schema-validation})))]
                    (save! ctx {:revn 2
                                :changes [(set-attr page-id shape-b :name "renamed")]}))]
      (t/is (= :referential-integrity (:code (ex-data (:error out)))))
      (t/is (= (inc before-unrepaired) (unrepaired)))
      (t/is (= (inc before)
               (refused "referential" "referential-integrity" "refused")))
      (t/is (= before-schema
               (refused "schema" "referential-integrity" "refused"))))))

(t/deftest a-failure-without-a-type-is-one-series-on-both-request-shapes
  ;; The debug fault is raised before the change step starts its timer,
  ;; hence `pre-changes`.
  (with-redefs [cf/flags (conj cf/flags :debug-save-faults)]
    (let [ctx       (setup-file)
          before   (refused "pre-changes" "unexpected-error" "refused")
          whole    (save! ctx {:changes [(debug-fault "runtime")]})
          _        (t/is (= (inc before)
                            (refused "pre-changes" "unexpected-error" "refused")))
          by-commit (save! ctx {:changes-with-metadata [(commit (debug-fault "runtime"))]})]
      (t/is (some? (:error whole)))
      (t/is (= :unexpected-error (-> by-commit :result :rejected :error :code)))
      (t/is (= (+ 2 before)
               (refused "pre-changes" "unexpected-error" "refused"))))))

(t/deftest an-infrastructure-failure-is-not-counted-as-a-validation-code
  (with-redefs [cf/flags (conj cf/flags :debug-save-faults)]
    (let [ctx    (setup-file)
          before (refused "pre-changes" "infrastructure" "refused")
          out    (save! ctx {:changes [(debug-fault "sql")]})]
      (t/is (instance? java.sql.SQLException (:error out)))
      (t/is (= (inc before)
               (refused "pre-changes" "infrastructure" "refused"))))))

(t/deftest partial-rejection-is-counted-with-its-outcome-and-rejected-commits
  ;; A save that carries commit groups keeps the commits before the first
  ;; invalid one and answers normally, so nothing raises out of
  ;; `update-file` and the refusal counter in its error handler never
  ;; sees the rejection.
  (let [{:keys [page-id] :as ctx} (setup-file)
        commits         [(page-name-commit page-id "a")
                         (page-name-commit page-id "b")
                         (invalid-type-commit page-id)
                         (page-name-commit page-id "c")]
        before          (refused "changes" "data-validation" "partial")
        before-refused  (refused "changes" "data-validation" "refused")
        rejected-before (rejected-commits "data-validation")
        out             (save! ctx {:changes-with-metadata commits})]
    ;; The two commits before the invalid one apply and validate
    (t/is (= (mapv :commit-id (subvec commits 2))
             (-> out :result :rejected :commit-ids)))
    (t/is (= (inc before)
             (refused "changes" "data-validation" "partial")))
    (t/is (= before-refused
             (refused "changes" "data-validation" "refused")))
    (t/is (= (+ 2 rejected-before)
             (rejected-commits "data-validation")))))

(t/deftest a-partial-save-that-fails-to-store-is-counted-once
  ;; The write fails after the commits were sorted: nothing is stored, so
  ;; the save is one refusal and not also a partial one.
  (let [{:keys [page-id] :as ctx} (setup-file)
        commits        [(page-name-commit page-id "a")
                        (invalid-type-commit page-id)]
        before-partial (refused "changes" "data-validation" "partial")
        before         (refused "changes" "infrastructure" "refused")
        out            (with-redefs [files-update/persist-file!
                                     (fn [_ _]
                                       (throw (java.sql.SQLException. "write failed")))]
                         (save! ctx {:changes-with-metadata commits}))]
    (t/is (instance? java.sql.SQLException (:error out)))
    (t/is (= before-partial
             (refused "changes" "data-validation" "partial")))
    (t/is (= (inc before)
             (refused "changes" "infrastructure" "refused")))))

(t/deftest a-save-that-rejects-every-commit-is-counted-as-a-refusal
  ;; The request answers normally and stores nothing, so `refused` is
  ;; the only outcome that separates it from a save that went through.
  (let [{:keys [page-id] :as ctx} (setup-file)
        commits [(invalid-type-commit page-id)]
        before  (refused "changes" "data-validation" "refused")
        out     (save! ctx {:changes-with-metadata commits})]
    (t/is (nil? (:error out)))
    (t/is (= (mapv :commit-id commits)
             (-> out :result :rejected :commit-ids)))
    (t/is (= (inc before)
             (refused "changes" "data-validation" "refused")))))

(t/deftest lagged-save-is-counted
  (let [{:keys [profile-id file page-id]} (setup-file)
        changes [{:type :mod-obj :page-id page-id :id uuid/zero :operations []}]
        metrics (:app.metrics/metrics th/*system*)
        before  (counter metrics "penpot_rpc_update_file_lagged_total" [] [])]

    ;; The first save moves the stored revn to 1; it arrives at the head.
    (th/update-file! :profile-id profile-id
                     :file-id (:id file)
                     :revn 0
                     :changes changes)
    (t/is (= before
             (counter metrics "penpot_rpc_update_file_lagged_total" [] [])))

    ;; The second one keeps the stale revn and the server accepts it,
    ;; applying it on top of the change its client has not received.
    (th/update-file! :profile-id profile-id
                     :file-id (:id file)
                     :revn 0
                     :changes changes)
    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_lagged_total" [] [])))))
