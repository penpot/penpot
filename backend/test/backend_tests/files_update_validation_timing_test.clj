;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.files-update-validation-timing-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.files.validate :as val]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main :as main]
   [app.metrics :as mtx]
   [app.metrics.definition :as-alias mdef]
   [app.rpc.commands.files-update :as files-update]
   [clojure.test :as t]
   [integrant.core :as ig])
  (:import
   io.prometheus.client.CollectorRegistry))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- bucket-value
  [metrics mname label-names label-values le]
  (.getSampleValue ^CollectorRegistry (mtx/get-registry metrics)
                   (str mname "_bucket")
                   (into-array String (conj (vec label-names) "le"))
                   (into-array String (conj (vec label-values) le))))

(defn- test-file
  []
  {:id (uuid/next)
   :revn 0
   :version 0
   :data {}})

(defn- test-cfg
  [metrics]
  {::mtx/metrics metrics
   ::files-update/validation-timings (atom {})})

(def ^:private all-flags
  #{:file-validation
    :file-schema-validation
    :soft-file-validation
    :soft-file-schema-validation})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest validation-timing-metric-uses-request-buckets
  (t/is (= mtx/default-request-histogram-buckets
           (get-in main/default-metrics [:update-file-validation-timing ::mdef/buckets])))
  (t/is (= "penpot_rpc_update_file_validation_timing"
           (get-in main/default-metrics [:update-file-validation-timing ::mdef/name]))))

(t/deftest process-changes-and-validate-records-all-steps
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})
        cfg (test-cfg metrics)]
    (binding [cf/flags all-flags]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})
                    val/validate-file-affected! (fn [_ _ _] nil)
                    val/validate-file-schema! (fn [_] nil)]
        (let [file (@#'files-update/process-changes-and-validate cfg (test-file) [] nil)
              timings @(::files-update/validation-timings cfg)]
          (t/is (map? file))
          (t/is (number? (:resolve-libs-ms timings)))
          (t/is (number? (:process-changes-ms timings)))
          (t/is (number? (:soft-validate-affected-ms timings)))
          (t/is (number? (:soft-validate-schema-ms timings)))
          (t/is (number? (:validate-affected-ms timings)))
          (t/is (number? (:validate-schema-ms timings)))
          (doseq [step ["resolve-libs" "process-changes"
                        "soft-validate-affected" "soft-validate-schema"
                        "validate-affected" "validate-schema"]]
            (t/is (= 1.0 (bucket-value metrics "penpot_rpc_update_file_validation_timing"
                                       ["step"] [step] "60000.0"))
                  (str "expected one sample for step " step))))))))

(t/deftest process-changes-and-validate-skips-when-skip-validate
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})
        cfg (test-cfg metrics)]
    (binding [cf/flags all-flags]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _]
                                                      (throw (ex-info "must not resolve libs" {})))
                    val/validate-file-affected! (fn [_ _ _]
                                                  (throw (ex-info "must not validate" {})))
                    val/validate-file-schema! (fn [_]
                                                (throw (ex-info "must not validate schema" {})))]
        ;; soft validations still run: they only check flags, not skip-validate
        (let [file (@#'files-update/process-changes-and-validate cfg (test-file) [] true)
              timings @(::files-update/validation-timings cfg)]
          (t/is (map? file))
          (t/is (nil? (:resolve-libs-ms timings)))
          (t/is (number? (:process-changes-ms timings)))
          (t/is (number? (:soft-validate-affected-ms timings)))
          (t/is (number? (:soft-validate-schema-ms timings)))
          (t/is (nil? (:validate-affected-ms timings)))
          (t/is (nil? (:validate-schema-ms timings)))
          (t/is (= 1.0 (bucket-value metrics "penpot_rpc_update_file_validation_timing"
                                     ["step"] ["process-changes"] "60000.0"))))))))

(t/deftest process-changes-and-validate-records-timing-on-error
  (let [metrics (ig/init-key :app.metrics/metrics {:default main/default-metrics})
        cfg (test-cfg metrics)]
    (binding [cf/flags all-flags]
      (with-redefs [bfc/get-resolved-file-libraries (fn [_ _] {})
                    val/validate-file-affected! (fn [_ _ _]
                                                  (throw (ex-info "bad file" {:code :referential-integrity})))
                    val/validate-file-schema! (fn [_] nil)]
        (let [timings-atom (::files-update/validation-timings cfg)]
          (t/is (thrown? clojure.lang.ExceptionInfo
                         (@#'files-update/process-changes-and-validate cfg (test-file) [] nil)))
          ;; the failing step still recorded its timing via finally
          (t/is (number? (:validate-affected-ms @timings-atom)))
          (t/is (number? (:resolve-libs-ms @timings-atom)))
          (t/is (number? (:process-changes-ms @timings-atom))))))))

(t/deftest record-validation-timing-tolerates-missing-collaborators
  ;; production code must not blow up when called without metrics
  ;; (e.g. scripts) or without the timings collector.
  (let [record @#'files-update/record-validation-timing]
    (t/is (nil? (record {} :resolve-libs-ms "resolve-libs" 12)))
    (t/is (nil? (record {::mtx/metrics nil} :resolve-libs-ms "resolve-libs" 12)))))
