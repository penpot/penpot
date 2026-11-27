;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.files-update-refusal-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.files.validate :as val]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry
   java.sql.SQLException))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-metrics
  []
  (ig/init-key :app.metrics/metrics {:default main/default-metrics}))

(defn- counter
  [metrics id label-names label-values]
  (or (.getSampleValue ^CollectorRegistry (mtx/get-registry metrics)
                       ^String (str "penpot_rpc_update_file_" id "_total")
                       (into-array String label-names)
                       (into-array String label-values))
      0.0))

(defn- refused
  [stage code outcome]
  (counter (::mtx/metrics th/*system*) "refused"
           ["stage" "code" "outcome"] [stage code outcome]))

(defn- rejected-commits
  [code]
  (counter (::mtx/metrics th/*system*) "rejected_commits" ["code"] [code]))

(defn- soft-failures
  [metrics check reason]
  (counter metrics "soft_failure" ["check" "reason"] [check reason]))

(defn- lagged
  []
  (counter (::mtx/metrics th/*system*) "lagged" [] []))

(defn- create-file
  []
  (let [idx     (uuid/next)
        profile (th/create-profile* idx {:is-active true})
        file    (th/create-file* idx {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (first (:pages (:data file)))}))

(defn- params
  [{:keys [profile-id file-id]} values]
  (merge {::rpc/profile-id profile-id
          :id file-id
          :session-id (uuid/next)
          :revn 0
          :vern 0
          :features (cfeat/get-enabled-features cf/flags)}
         values))

(defn- save
  [ctx command values]
  (th/command! (assoc (params ctx values) ::th/type command)))

(defn- grouped
  [command commits]
  (if (= command :update-file)
    {:changes-with-metadata commits}
    {:commits commits}))

(defn- commit
  [& changes]
  {:commit-id (uuid/next) :changes (vec changes)})

(defn- set-attr
  [page-id shape-id attr value]
  {:type :mod-obj
   :page-id page-id
   :id shape-id
   :operations [{:type :set :attr attr :val value}]})

(defn- invalid-type
  [page-id]
  (set-attr page-id uuid/zero :type :not-a-shape))

(defn- rename-page
  [page-id name]
  {:type :mod-page :id page-id :name name})

(defn- debug-fault
  [fault]
  {:type :set-plugin-data
   :object-type :file
   :namespace :penpot-debug
   :key (str "save-fault:" fault)
   :value nil})

(defn- revisions
  [file-id]
  (filterv :changes (th/db-query :file-change {:file-id file-id})))

(defn- validation-file
  [shape]
  (let [page-id (uuid/next)]
    {:id (uuid/next)
     :revn 0
     :version 0
     :features #{"components/v2"}
     :data {:pages [page-id]
            :pages-index {page-id {:id page-id
                                   :name "Page"
                                   :objects {(:id shape) shape}}}}}))

(defn- validate
  [metrics flag file]
  (let [cfg     {::mtx/metrics metrics
                 ::files-update/validation-timings (atom {})}
        page-id (first (get-in file [:data :pages]))]
    (binding [cf/flags #{flag}
              pmap/*tracked* (pmap/create-tracked)]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})]
        (@#'files-update/process-changes-and-validate
         cfg file [(rename-page page-id "Renamed")] nil false)))))

(t/deftest soft-schema-failure-uses-the-deepest-declared-key
  (let [metrics (create-metrics)
        shape   (cts/setup-shape {:type :rect :name "rect" :x 0 :y 0 :width 10 :height 10})
        invalid (validation-file (assoc shape :type :not-a-shape))]
    (t/is (map? (validate metrics :soft-file-schema-validation invalid)))
    (t/is (= 1.0 (soft-failures metrics "schema" "type")))
    (t/is (map? (validate nil :soft-file-schema-validation invalid)))
    (doseq [extra [:extra-attr-one :extra-attr-two]]
      (let [file (validation-file (assoc shape :svg-attrs {extra "value"}))]
        (t/is (map? (validate metrics :soft-file-schema-validation file)))
        (t/is (zero? (soft-failures metrics "schema" (name extra))))))
    (t/is (= 2.0 (soft-failures metrics "schema" "svg-attrs")))))

(t/deftest soft-referential-failure-uses-the-first-reported-code
  (let [metrics (create-metrics)
        root    (cts/setup-shape {:id uuid/zero :type :frame :name "Root"
                                  :x 0 :y 0 :width 100 :height 100
                                  :parent-id uuid/zero :frame-id uuid/zero})
        file    (validation-file (assoc root :shapes [(uuid/next)]))]
    (t/is (map? (validate metrics :soft-file-validation file)))
    (t/is (= 1.0 (soft-failures metrics "affected" "child-not-found")))))

(t/deftest unclassified-soft-failures-do-not-reject-the-save
  (let [metrics (create-metrics)
        file    (validation-file (cts/setup-shape {:type :rect :name "rect"
                                                   :x 0 :y 0 :width 10 :height 10}))]
    (with-redefs [val/validate-file-schema-affected!
                  (fn [_ _] (throw (ex-info "no schema explain" {})))
                  val/validate-file-affected!
                  (fn [_ _ _]
                    (throw (ex-info "unrecognized error code" {:details [{:code :unknown-code}]})))]
      (t/is (map? (validate metrics :soft-file-schema-validation file)))
      (t/is (map? (validate metrics :soft-file-validation file))))
    (t/is (= 1.0 (soft-failures metrics "schema" "unknown")))
    (t/is (= 1.0 (soft-failures metrics "affected" "unknown")))
    (t/is (zero? (soft-failures metrics "affected" "unknown-code")))))

(t/deftest bisection-does-not-count-soft-failures-again
  (let [metrics (create-metrics)
        cfg     {::mtx/metrics metrics
                 ::files-update/validation-timings (atom {})}
        file    (validation-file (cts/setup-shape {:type :rect :name "rect"
                                                   :x 0 :y 0 :width 10 :height 10}))
        page-id (first (get-in file [:data :pages]))
        groups  (mapv (fn [name] {:commit-id (uuid/next)
                                  :redo-changes [(rename-page page-id name)]})
                      ["Accepted" "Rejected" "Later"])
        changes (into [] (mapcat :redo-changes) groups)
        checks  (atom 0)]
    (binding [cf/flags #{:soft-file-validation :file-schema-validation}
              pmap/*tracked* (pmap/create-tracked)]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})
                    val/validate-file-affected!
                    (fn [_ _ _]
                      (swap! checks inc)
                      (throw (ex-info "broken reference" {:type :validation
                                                          :details [{:code :child-not-found}]})))
                    val/validate-file-schema-affected!
                    (fn [_ changes]
                      (when (some #(= "Rejected" (:name %)) changes)
                        (throw (ex-info "broken schema" {:type :validation}))))]
        (let [result (@#'files-update/process-changes-and-validate cfg file changes groups false)]
          (t/is (= (mapv :commit-id (subvec groups 1))
                   (get-in result [::files-update/rejected :commit-ids]))))))
    (t/is (< 1 @checks))
    (t/is (= 1.0 (soft-failures metrics "affected" "child-not-found")))))

(t/deftest hard-failures-count-the-stage-and-code
  (let [{:keys [page-id] :as ctx} (create-file)
        before (refused "changes" "data-validation" "refused")
        out    (save ctx :update-file {:changes [(invalid-type page-id)]})]
    (t/is (= :data-validation (-> out :error ex-data :code)))
    (t/is (= (inc before) (refused "changes" "data-validation" "refused"))))

  (binding [cf/flags (conj cf/flags :file-schema-validation)]
    (let [{:keys [page-id] :as ctx} (create-file)
          before (refused "schema" "validation" "refused")
          cause  (ex-info "schema failed" {:type :validation})
          out    (with-redefs [val/validate-file-schema-affected! (fn [_ _] (throw cause))]
                   (save ctx :update-file {:changes [(rename-page page-id "Rejected")]}))]
      (t/is (identical? cause (:error out)))
      (t/is (= (inc before) (refused "schema" "validation" "refused"))))))

(t/deftest failed-repair-does-not-overwrite-the-first-failure-stage
  (binding [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (let [{:keys [page-id] :as ctx} (create-file)
          a          (uuid/next)
          b          (uuid/next)
          initial    (save ctx :update-file {:changes [(th/add-rect-change page-id a)
                                                       (th/add-rect-change page-id b)]})
          damaged    (save ctx :update-file {:revn 1 :skip-validate true
                                             :changes [(set-attr page-id a :frame-id (uuid/next))]})
          before     (refused "referential" "referential-integrity" "refused")
          schema     (refused "schema" "referential-integrity" "refused")
          unrepaired #(counter (::mtx/metrics th/*system*) "repair" ["outcome"] ["unrepaired"])
          repair     (unrepaired)
          out        (with-redefs [val/validate-file-schema!
                                   (fn [_] (throw (ex-info "schema failed" {:type :validation})))]
                       (save ctx :update-file {:revn 2 :changes [(set-attr page-id b :name "Edited")]}))]
      (t/is (th/success? initial))
      (t/is (th/success? damaged))
      (t/is (= :referential-integrity (-> out :error ex-data :code)))
      (t/is (= (inc repair) (unrepaired)))
      (t/is (= (inc before) (refused "referential" "referential-integrity" "refused")))
      (t/is (= schema (refused "schema" "referential-integrity" "refused"))))))

(t/deftest infrastructure-and-unexpected-failures-use-distinct-codes
  (binding [cf/flags (conj cf/flags :debug-save-faults)]
    (let [ctx        (create-file)
          refused-db (refused "pre-changes" "infrastructure" "refused")
          refused-ex (refused "pre-changes" "unexpected-error" "refused")
          db-out     (save ctx :update-file {:changes [(debug-fault "sql")]})
          flat-out   (save ctx :update-file {:changes [(debug-fault "runtime")]})
          group-out  (save ctx :update-file-v2 {:commits [(commit (debug-fault "runtime"))]})]
      (t/is (instance? SQLException (:error db-out)))
      (t/is (instance? RuntimeException (:error flat-out)))
      (t/is (= :unexpected-error (get-in group-out [:result :rejected :error :code])))
      (t/is (= (inc refused-db) (refused "pre-changes" "infrastructure" "refused")))
      (t/is (= (+ 2 refused-ex) (refused "pre-changes" "unexpected-error" "refused"))))))

(t/deftest partial-saves-count-the-rejected-suffix-in-both-protocols
  (doseq [command [:update-file :update-file-v2]]
    (t/testing (name command)
      (let [{:keys [page-id file-id] :as ctx} (create-file)
            good    (commit (rename-page page-id "Accepted"))
            bad     (commit (invalid-type page-id))
            later   (commit (rename-page page-id "Rejected"))
            partial (refused "changes" "data-validation" "partial")
            whole   (refused "changes" "data-validation" "refused")
            n       (rejected-commits "data-validation")
            out     (save ctx command (grouped command [good bad later]))
            result  (:result out)
            rows    (revisions file-id)]
        (t/is (th/success? out))
        (t/is (= (mapv :commit-id [bad later]) (get-in result [:rejected :commit-ids])))
        (t/is (= [(:changes good)] (mapv (comp blob/decode :changes) rows)))
        (if (= command :update-file)
          (t/is (= [(:changes good)] (mapv :changes (:lagged result))))
          (do
            (t/is (not (contains? result :lagged)))
            (t/is (= 1 (:saved-revn result)))
            (t/is (= (mapv :id rows) (mapv :id (:written result))))))
        (t/is (= (inc partial) (refused "changes" "data-validation" "partial")))
        (t/is (= whole (refused "changes" "data-validation" "refused")))
        (t/is (= (+ 2 n) (rejected-commits "data-validation")))))))

(t/deftest accepting-an-empty-prefix-counts-as-partial-without-a-write
  (let [{:keys [page-id file-id] :as ctx} (create-file)
        partial (refused "changes" "data-validation" "partial")
        whole   (refused "changes" "data-validation" "refused")
        n       (rejected-commits "data-validation")
        bad     (commit (invalid-type page-id))
        result  (:result (save ctx :update-file-v2 {:commits [(commit) bad]}))]
    (t/is (= [(:commit-id bad)] (get-in result [:rejected :commit-ids])))
    (t/is (= [] (:written result)))
    (t/is (not (contains? result :saved-revn)))
    (t/is (empty? (revisions file-id)))
    (t/is (= (inc partial) (refused "changes" "data-validation" "partial")))
    (t/is (= whole (refused "changes" "data-validation" "refused")))
    (t/is (= (inc n) (rejected-commits "data-validation")))))

(t/deftest an-admitted-stale-revision-counts-even-if-all-commits-are-rejected
  (doseq [command [:update-file :update-file-v2]]
    (t/testing (name command)
      (let [{:keys [page-id file-id] :as ctx} (create-file)
            before  (lagged)
            initial (save ctx command (grouped command [(commit (rename-page page-id "First"))]))
            whole   (refused "changes" "data-validation" "refused")
            n       (rejected-commits "data-validation")
            bad     (commit (invalid-type page-id))
            grouped (grouped command [bad])
            future  (refused "pre-changes" "revn-conflict" "refused")
            version (refused "pre-changes" "vern-conflict" "refused")]
        (t/is (th/success? initial))
        (t/is (= before (lagged)))
        (t/is (= :revn-conflict (-> (save ctx command (assoc grouped :revn 2)) :error ex-data :code)))
        (t/is (= :vern-conflict (-> (save ctx command (assoc grouped :vern 1)) :error ex-data :code)))
        (t/is (= before (lagged)))
        (t/is (= future (refused "pre-changes" "revn-conflict" "refused")))
        (t/is (= version (refused "pre-changes" "vern-conflict" "refused")))
        (let [out (save ctx command grouped)]
          (t/is (th/success? out))
          (t/is (= [(:commit-id bad)] (get-in out [:result :rejected :commit-ids])))
          (t/is (= (inc before) (lagged)))
          (t/is (= (inc whole) (refused "changes" "data-validation" "refused")))
          (t/is (= (inc n) (rejected-commits "data-validation")))
          (t/is (= 1 (:revn (th/db-get :file {:id file-id}))))
          (t/is (= 1 (count (revisions file-id)))))))))

(t/deftest failure-to-persist-a-partial-save-counts-only-as-infrastructure
  (let [{:keys [page-id file-id] :as ctx} (create-file)
        commits [(commit (rename-page page-id "Accepted")) (commit (invalid-type page-id))]
        partial (refused "changes" "data-validation" "partial")
        whole   (refused "changes" "infrastructure" "refused")
        n       (rejected-commits "data-validation")
        cause   (SQLException. "write failed")
        out     (with-redefs [files-update/persist-file! (fn [_ _] (throw cause))]
                  (save ctx :update-file-v2 {:commits commits}))]
    (t/is (identical? cause (:error out)))
    (t/is (empty? (revisions file-id)))
    (t/is (= partial (refused "changes" "data-validation" "partial")))
    (t/is (= (inc whole) (refused "changes" "infrastructure" "refused")))
    (t/is (= n (rejected-commits "data-validation")))))

(t/deftest rolling-back-a-prepared-partial-save-does-not-count-it
  (let [{:keys [page-id file-id] :as ctx} (create-file)
        bad     (commit (invalid-type page-id))
        commits [(commit (rename-page page-id "Accepted")) bad]
        partial (refused "changes" "data-validation" "partial")
        whole   (refused "changes" "data-validation" "refused")
        n       (rejected-commits "data-validation")]
    (db/tx-run! (assoc th/*system* ::db/rollback true)
                (fn [{:keys [::db/conn] :as cfg}]
                  (let [result (files-update/sm$update-file-v2 cfg (params ctx {:commits commits}))]
                    (t/is (= [(:commit-id bad)] (get-in result [:rejected :commit-ids])))
                    (t/is (= 1 (:revn (db/get* conn :file {:id file-id}))))
                    (t/is (= 1 (count (filter :changes (db/query conn :file-change {:file-id file-id})))))
                    (t/is (= partial (refused "changes" "data-validation" "partial")))
                    (t/is (= n (rejected-commits "data-validation"))))))
    (t/is (= 0 (:revn (th/db-get :file {:id file-id}))))
    (t/is (empty? (revisions file-id)))
    (t/is (= partial (refused "changes" "data-validation" "partial")))
    (t/is (= whole (refused "changes" "data-validation" "refused")))
    (t/is (= n (rejected-commits "data-validation")))))
