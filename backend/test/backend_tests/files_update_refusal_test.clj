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
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
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

(defn- validation-cfg
  [metrics]
  {::mtx/metrics metrics
   ::files-update/validation-timings (atom {})
   ::files-update/incoming-revn 0
   ::files-update/stored-revn 0})

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

(defn- valid-file
  "A file with one valid page: a root frame that lists its only child."
  []
  (let [page-id  (uuid/next)
        child-id (uuid/next)
        child    (cts/setup-shape {:id child-id
                                   :type :rect
                                   :name "rect"
                                   :x 0 :y 0 :width 10 :height 10
                                   :parent-id uuid/zero
                                   :frame-id uuid/zero})
        root     (-> (cts/setup-shape {:id uuid/zero
                                       :type :frame
                                       :name "Root"
                                       :x 0 :y 0 :width 100 :height 100
                                       :parent-id uuid/zero
                                       :frame-id uuid/zero})
                     (assoc :shapes [child-id]))]
    {:id (uuid/next)
     :revn 0
     :version 0
     :features #{"components/v2"}
     :data {:pages [page-id]
            :pages-index {page-id {:id page-id
                                   :name "Page 1"
                                   :objects {uuid/zero root child-id child}}}}}))

(defn- touch-first-page
  "A change that touches the first page of `file` and leaves it as it
  was: the schema check covers only the pages a save touches."
  [file]
  [{:type :mod-obj
    :page-id (first (:pages (:data file)))
    :id uuid/zero
    :operations []}])

(defn- invalid-type-commit
  "A commit group whose fold leaves a shape invalid."
  [file]
  {:commit-id (uuid/next)
   :changes [{:type :mod-obj
              :page-id (first (:pages (:data file)))
              :id uuid/zero
              :operations [{:type :set
                            :attr :type
                            :val :not-a-shape}]}]})

(defn- page-name-commit
  "A commit group that applies and validates."
  [file name]
  {:commit-id (uuid/next)
   :changes [{:type :mod-page
              :id (first (:pages (:data file)))
              :name name}]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest refusal-metrics-are-declared-with-the-labels-they-receive
  ;; A counter whose declared labels differ from the ones the recorder
  ;; passes fails inside `run!`, which swallows the error: every sample
  ;; would be dropped silently.
  (t/is (= ["stage" "code" "outcome"]
           (get-in main/default-metrics [:update-file-refused ::mdef/labels])))
  (t/is (= :counter
           (get-in main/default-metrics [:update-file-refused ::mdef/type])))

  (t/is (= ["code"]
           (get-in main/default-metrics [:update-file-rejected-commits ::mdef/labels])))
  (t/is (= :counter
           (get-in main/default-metrics [:update-file-rejected-commits ::mdef/type])))

  (t/is (= ["check" "reason"]
           (get-in main/default-metrics [:update-file-soft-failure ::mdef/labels])))
  (t/is (= :counter
           (get-in main/default-metrics [:update-file-soft-failure ::mdef/type])))

  (t/is (nil? (get-in main/default-metrics [:update-file-lagged ::mdef/labels])))
  (t/is (= :counter
           (get-in main/default-metrics [:update-file-lagged ::mdef/type]))))

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

(t/deftest referential-refusal-reports-the-gate-stage-and-code
  ;; The damage has to come from the changes. A file damaged before the
  ;; save is repairable, and the save then goes through instead of
  ;; refusing.
  (let [cfg      (validation-cfg (metrics))
        file     (valid-file)
        page-id  (first (:pages (:data file)))
        shape-id (first (remove #{uuid/zero}
                                (keys (get-in file [:data :pages-index page-id :objects]))))
        changes  [{:type :mod-obj
                   :page-id page-id
                   :id shape-id
                   :operations [{:type :set
                                 :attr :parent-id
                                 :val (uuid/next)}]}]
        cause    (binding [cf/flags #{:file-validation}
                           pmap/*tracked* (pmap/create-tracked)]
                   (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
                     (try
                       (@#'files-update/process-changes-and-validate cfg file changes nil nil)
                       nil
                       (catch Throwable cause
                         cause))))]
    (t/is (some? cause))
    (t/is (= "referential"
             (@#'files-update/refusal-stage
              @(::files-update/validation-timings cfg))))
    (t/is (= "referential-integrity"
             (@#'files-update/refusal-code cause)))))

(t/deftest soft-failure-records-nothing-without-a-metrics-instance
  ;; `mtx/run!` fails on a missing instance, so an unguarded recording
  ;; would turn a soft check into a refusal for callers without metrics.
  (let [cfg  {::files-update/validation-timings (atom {})
              ::files-update/incoming-revn 0
              ::files-update/stored-revn 0}
        file (broken-schema-file)]
    (binding [cf/flags #{:soft-file-schema-validation}
              pmap/*tracked* (pmap/create-tracked)]
      (t/is (map? (@#'files-update/process-changes-and-validate
                   cfg file (touch-first-page file) nil nil))))))

(t/deftest refused-update-file-is-counted-with-stage-and-code
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        page-id (first (:pages (:data file)))
        metrics (:app.metrics/metrics th/*system*)
        before  (counter metrics "penpot_rpc_update_file_refused_total"
                         ["stage" "code" "outcome"]
                         ["changes" "data-validation" "refused"])
        ;; The operation passes the params schema (`:val` is untyped) and
        ;; leaves the shape invalid, so the fold itself refuses it.
        out     (th/command! {::th/type :update-file
                              ::rpc/profile-id (:id profile)
                              :id (:id file)
                              :session-id (uuid/next)
                              :revn 0
                              :vern 0
                              :features (cfeat/get-enabled-features cf/flags)
                              :changes [{:type :mod-obj
                                         :page-id page-id
                                         :id uuid/zero
                                         :operations [{:type :set
                                                       :attr :type
                                                       :val :not-a-shape}]}]})]
    (t/is (some? (:error out)))
    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_refused_total"
                      ["stage" "code" "outcome"]
                      ["changes" "data-validation" "refused"])))))

(t/deftest partial-rejection-is-counted-with-its-outcome-and-rejected-commits
  ;; A save that carries commit groups keeps the commits before the first
  ;; invalid one and answers normally, so nothing raises out of
  ;; `update-file` and the refusal counter in its error handler never
  ;; sees the rejection.
  (let [metrics  (metrics)
        cfg      (validation-cfg metrics)
        file     (valid-file)
        commits  [(page-name-commit file "a")
                  (page-name-commit file "b")
                  (invalid-type-commit file)
                  (page-name-commit file "c")]
        before   (counter metrics "penpot_rpc_update_file_refused_total"
                          ["stage" "code" "outcome"]
                          ["changes" "data-validation" "partial"])
        rejected-before (counter metrics "penpot_rpc_update_file_rejected_commits_total"
                                 ["code"] ["data-validation"])]
    (binding [cf/flags #{}
              pmap/*tracked* (pmap/create-tracked)]
      (let [saved (@#'files-update/process-changes-and-validate
                   cfg file (into [] (mapcat :changes) commits) commits nil)]
        ;; The two commits before the invalid one apply and validate
        (t/is (= (mapv :commit-id (subvec commits 2))
                 (:commit-ids (::files-update/rejected saved))))))

    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_refused_total"
                      ["stage" "code" "outcome"]
                      ["changes" "data-validation" "partial"])))
    (t/is (= (+ 2 rejected-before)
             (counter metrics "penpot_rpc_update_file_rejected_commits_total"
                      ["code"] ["data-validation"])))))

(t/deftest a-save-that-rejects-every-commit-is-counted-as-a-refusal
  ;; The request answers normally and stores nothing, so `refused` is
  ;; the only outcome that separates it from a save that went through.
  (let [metrics (metrics)
        cfg     (validation-cfg metrics)
        file    (valid-file)
        commits [(invalid-type-commit file)]
        before  (counter metrics "penpot_rpc_update_file_refused_total"
                         ["stage" "code" "outcome"]
                         ["changes" "data-validation" "refused"])]
    (binding [cf/flags #{}
              pmap/*tracked* (pmap/create-tracked)]
      (let [saved (@#'files-update/process-changes-and-validate
                   cfg file (into [] (mapcat :changes) commits) commits nil)]
        (t/is (= (mapv :commit-id commits)
                 (:commit-ids (::files-update/rejected saved))))))

    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_refused_total"
                      ["stage" "code" "outcome"]
                      ["changes" "data-validation" "refused"])))))

(t/deftest lagged-save-is-counted
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        page-id (first (:pages (:data file)))
        changes [{:type :mod-obj :page-id page-id :id uuid/zero :operations []}]
        metrics (:app.metrics/metrics th/*system*)
        before  (counter metrics "penpot_rpc_update_file_lagged_total" [] [])]

    ;; The first save moves the stored revn to 1; it arrives at the head.
    (th/update-file! :profile-id (:id profile)
                     :file-id (:id file)
                     :revn 0
                     :changes changes)
    (t/is (= before
             (counter metrics "penpot_rpc_update_file_lagged_total" [] [])))

    ;; The second one keeps the stale revn and the server accepts it,
    ;; applying it on top of the change its client has not received.
    (th/update-file! :profile-id (:id profile)
                     :file-id (:id file)
                     :revn 0
                     :changes changes)
    (t/is (= (inc before)
             (counter metrics "penpot_rpc_update_file_lagged_total" [] [])))))
