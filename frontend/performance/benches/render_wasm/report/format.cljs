;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.format
  "Text formatting for run summaries and saved comparisons.

  Text leads with observed median, bootstrap interval and distinct counts.
  Full statistics and acknowledged differences include units and phase identity."
  (:require
   [benches.render-wasm.errors :as errors]
   [benches.render-wasm.result :as result]
   [clojure.string :as str]))

(defn- number->text
  "Formats safe integers in decimal form and other estimates with six significant digits."
  [value]
  (if (js/Number.isSafeInteger value)
    (str value)
    (.toPrecision value 6)))

(defn- estimate->text
  "Formats an estimate, interval or suppression reason."
  [estimate]
  (cond
    (contains? estimate :value) (number->text (:value estimate))
    (contains? estimate :lower) (str "[" (number->text (:lower estimate)) ", "
                                     (number->text (:upper estimate)) "]")
    :else (str "unavailable (" (some-> (:reason estimate) name)
               (when-some [n (:minimum estimate)] (str "; need " n)) ")")))

(defn- counts->text
  "Prints valid, invalid, absent, failed, unattempted and excluded warmup counts."
  [{:keys [valid invalid absent failed unattempted warmup invocations raw-values]}]
  (str "valid=" valid " invalid=" invalid " absent=" absent " failed=" failed
       " unattempted=" unattempted " warmup=" warmup
       " invocations=" invocations " raw-values=" raw-values " n=" valid))

(defn metric->text
  "Formats statistics across phase execution sums with their units and counts.

  Args:
    id: vector of phase, command or global scope, and metric type.
    report: quantity descriptions, accounting counts, statistics and diagnostics.

  Returns:
    Text with observed estimates, support limits and raw-value counts."
  [id {:keys [definitions counts statistics diagnostics]}]
  (str (pr-str id) " sum per execution; median " (estimate->text (or (:median statistics) statistics))
       " CI " (estimate->text (or (:median-ci statistics) statistics)) " | " (counts->text counts)
       "\n  definitions " (pr-str definitions)
       (when (seq diagnostics) (str "\n  diagnostics " (pr-str diagnostics)))
       (if-let [reason (:reason statistics)]
         (str "\n  unavailable (" (name reason) ")")
         (str "\n  mean " (estimate->text (:mean statistics))
              " min " (estimate->text (:min statistics)) " max " (estimate->text (:max statistics))
              " sample SD " (estimate->text (:sample-sd statistics))
              " MAD " (estimate->text (:mad statistics))
              " p95 " (estimate->text (:p95 statistics)) " p99 " (estimate->text (:p99 statistics))))))

(defn- quoted-detail
  [value]
  (errors/bounded-text (js/JSON.stringify value) 1024))

(defn format-error-entry
  "Formats a failure location with its available operation, message and path."
  [{:keys [phase operation message path]}]
  (str (when operation (str (pr-str operation) " "))
       (quoted-detail phase)
       (when (some? message) (str ": " (quoted-detail message)))
       (when path (str "\n      Path " (quoted-detail path)))))

(defn- failure->text
  [{:keys [failure reason]}]
  (if failure
    (if-let [n (:unacknowledged-differences failure)]
      (str n " unacknowledged " (if (= n 1) "difference" "differences"))
      (if-let [entries (seq (:errors failure))]
        (str (if (not= (errors/bounded-text (:message failure) 1024) (:message (first entries)))
               (str (format-error-entry failure) "\n    ")
               (when (not= (select-keys failure [:phase :operation :path])
                           (select-keys (first entries) [:phase :operation :path]))
                 (str "Context " (format-error-entry (dissoc failure :message)) "\n    ")))
             (str/join "\n    " (map format-error-entry (take 8 entries)))
             (when (> (count entries) 8)
               (str "\n    " (- (count entries) 8) " more errors recorded")))
        (format-error-entry failure)))
    (quoted-detail reason)))

(defn- identifier->text
  [value]
  (if (string? value)
    (quoted-detail value)
    (errors/bounded-text (pr-str value) 1024)))

(defn- same-diagnostic?
  [left right]
  (= (dissoc (errors/project-diagnostic left) :status :partial)
     (dissoc (errors/project-diagnostic right) :status :partial)))

(defn- case-failures
  [{:keys [case-id preparations failed-attempts]}]
  (let [preparations (filter :failure preparations)
        by-id        (into {} (map (juxt :preparation-id identity)) preparations)]
    (concat
     (map (fn [preparation]
            (assoc (select-keys preparation [:preparation-id :warmup? :failure])
                   :kind :preparation :case-id case-id))
          preparations)
     (keep (fn [{:keys [preparation-id failure phases] :as attempt}]
             (let [preparation (get by-id preparation-id)
                   copied?     (and preparation
                                    (every? #(= :preparation (:phase %)) phases)
                                    (same-diagnostic? failure (:failure preparation)))]
               (when (and failure (not copied?))
                 (assoc (select-keys attempt [:attempt-id :preparation-id :warmup? :failure :partial])
                        :kind :attempt :case-id case-id))))
           failed-attempts))))

(defn- run-failures
  [cases metadata termination]
  (let [case-entries (vec (mapcat case-failures cases))
        by-case      (group-by :case-id case-entries)
        harness      (keep-indexed
                      (fn [index failure]
                        (let [copied? (and (= :preparation (:stage failure))
                                           (some #(and (= :preparation (:kind %))
                                                       (same-diagnostic? (:failure %) failure))
                                                 (get by-case (:case-id failure))))]
                          (when-not copied?
                            {:kind :harness :index index :stage (:stage failure)
                             :case-id (:case-id failure) :failure failure})))
                      (get-in metadata [:diagnostics :failures]))
        recorded     (into case-entries harness)
        by-owner     (group-by :case-id recorded)
        outcomes     (keep (fn [{:keys [case-id outcome]}]
                             (when-let [failure (:failure outcome)]
                               (when-not (some #(same-diagnostic? failure (:failure %)) (get by-owner case-id))
                                 {:kind :case :case-id case-id :failure failure})))
                           cases)
        entries      (into recorded outcomes)]
    (cond-> entries
      (and (:failure termination)
           (not-any? #(same-diagnostic? (:failure termination) (:failure %)) entries))
      (conj {:kind :termination :failure (:failure termination)}))))

(defn- diagnostic->text
  [failure]
  (let [details (merge (select-keys (first (:cause failure)) [:type :code :fn])
                       (errors/project-context failure)
                       (select-keys failure [:reason :deadline-exceeded]))]
    (str (failure->text {:failure failure})
         (apply str (map (fn [[key value]]
                           (str "\n    " (name key) " " (identifier->text value)))
                         (sort-by (comp name key) (filter (comp some? val) details)))))))

(defn- failure-entry->text
  [{:keys [kind case-id preparation-id attempt-id index stage warmup? failure partial]}]
  (str "  " (case kind
              :preparation (str "Preparation " (identifier->text preparation-id))
              :attempt (str "Attempt " attempt-id " (preparation " (identifier->text preparation-id) ")")
              :harness (str "Harness " index " " (identifier->text stage))
              :case "Case outcome"
              :termination "Termination")
       (when (some? case-id) (str " (case " (identifier->text case-id) ")"))
       (when warmup? " warmup")
       ": " (diagnostic->text failure)
       (when (or partial (:partial failure)) "\n    Partial evidence recorded")))

(defn- failures->text
  [heading entries]
  (when (seq entries)
    (str "\n\n" heading "\n" (str/join "\n" (map failure-entry->text entries)))))

(defn- screenshots->text
  [heading metadata failures cases]
  (let [screenshots (get-in metadata [:diagnostics :screenshots])
        names       (frequencies (map result/case-name
                                      (distinct (concat (map :case-id cases) (keep :case-id failures)))))
        deadlines   (group-by #(result/case-name (:case-id %))
                              (filter #(and (= :harness (:kind %))
                                            (= :case-deadline (:stage %))
                                            (some? (:case-id %))) failures))]
    (when (seq screenshots)
      (str "\n\n" heading "\n"
           (str/join
            "\n"
            (map (fn [[case-name {:keys [status message reason file cause]}]]
                   (let [matches (when (and (= 1 (get names case-name)) (string? message) (seq cause))
                                   (filter #(and (= message (get-in % [:failure :message]))
                                                 (= cause (get-in % [:failure :cause])))
                                           (get deadlines case-name)))
                         related (when (= 1 (count matches)) (first matches))]
                     (str "  " (identifier->text case-name) ": "
                          (if related
                            (str "failed; see Harness " (:index related))
                            (case status
                              :failed "warning"
                              :ok "ok"
                              :skipped "skipped"
                              (identifier->text status)))
                          (when (and message (not related)) (str "; " (quoted-detail message)))
                          (when reason (str "; reason " (identifier->text reason)))
                          (when file (str "\n    File " (quoted-detail file))))))
                 (sort-by (comp str key) screenshots)))))))

(defn- case-status->text
  "Displays pending slots, phase counts and failed execution identities."
  [{:keys [remaining failed-attempts phases outcome]}]
  (str " remaining " (pr-str remaining)
       (when outcome (str "\n  outcome " (pr-str (:outcome outcome))))
       "\n  phases " (pr-str phases)
       " failed-warmups=" (count (filter :warmup? failed-attempts))
       (when (seq failed-attempts)
         (str "\n  failed attempts "
              (pr-str (mapv #(select-keys % [:attempt-id :preparation-id :warmup? :outcome]) failed-attempts))))))

(defn format-run
  "Formats run statistics, recorded failures and screenshot diagnostics."
  [{:keys [run-id metadata analysis termination source-coverage interpretation cases]}]
  (let [failures (run-failures cases metadata termination)]
    (str "Run " (pr-str run-id) " " (pr-str (dissoc termination :failure))
         "\nGit " (pr-str (:git metadata))
         "\nAnalysis " (pr-str analysis)
         "\n" source-coverage "\n" interpretation
         (when (some #(get-in % [:effective-graphics :software])
                     (mapcat :preparations cases)) "\nSOFTWARE RENDERER recorded")
         (apply str (map (fn [{:keys [case-id metrics] :as case-report}]
                           (str "\n\nCase " (result/case-name case-id)
                                (case-status->text case-report)
                                (apply str (map (fn [[id metric]] (str "\n" (metric->text id metric))) metrics))))
                         cases))
         (failures->text "Failures" failures)
         (screenshots->text "Screenshots" metadata failures cases))))

(defn- side->text
  "Displays one comparison side's definitions, preparation facts and observations."
  [label {:keys [case-id definition preparations metrics] :as side}]
  (str "\n" label " " (result/case-name case-id)
       (case-status->text side)
       "\n  case " (pr-str definition)
       "\n  preparations " (pr-str (mapv #(select-keys % [:preparation-id :warmup? :fingerprint :effective-graphics])
                                         preparations))
       (apply str (map (fn [[id metric]] (str "\n" (metric->text id metric))) metrics))))

(defn- configuration-failure->text
  [{:keys [slug run build] :as configuration}]
  (str "  Configuration " (quoted-detail slug) ": " (failure->text configuration)
       (cond
         run (str "\n    Run record " (quoted-detail (:record-path run))
                  "\n    Log " (quoted-detail (:log-path run)))
         build (str "\n    Build manifest " (quoted-detail (:manifest-path build))
                    "\n    Log " (quoted-detail (:log-path build))))))

(defn- edge-failure->text
  [{:keys [from to feature path] :as edge}]
  (str "  Comparison " (quoted-detail from) " -> " (quoted-detail to)
       " (feature " (quoted-detail feature) "): " (failure->text edge)
       (when path (str "\n    Report " (quoted-detail path)))))

(defn format-feature-comparison-failures
  "Formats failed configurations and comparisons with artifact references.

  Args:
    record: Validated feature-comparison record.
    home: Comparison :record-path and :log-path.

  Returns:
    Text for up to eight failures in configuration and comparison order,
    with an omitted count and references to the complete evidence. String
    fields escape C0 controls (U+0000 through U+001F) and use at most
    1024 UTF-16 units.
    Returns nil when the record contains no failed entries."
  [record home]
  (let [configurations (filter #(= :failed (:status %)) (:configurations record))
        edges          (filter #(= :failed (:status %)) (:edges record))
        total          (+ (count configurations) (count edges))
        entries        (concat (map configuration-failure->text configurations)
                               (map edge-failure->text edges))]
    (when (pos? total)
      (str "Failures\n" (str/join "\n" (take 8 entries))
           (when (> total 8) (str "\n  " (- total 8) " more failures in the saved record"))
           "\n  Record " (quoted-detail (:record-path home))
           "\n  Log " (quoted-detail (:log-path home))))))

(defn format-comparison
  "Formats selected permissions, acknowledged/unacknowledged differences and changes.
  Both saved definitions remain visible. Edge labels describe actual feature
  evidence, including equal effective sets, without predicting a timing effect."
  [{:keys [baseline candidate analysis permissions edge source-coverage interpretation pairs]}]
  (let [side-cases         (fn [side]
                             (let [cases (map side pairs)
                                   by-id (group-by :case-id cases)]
                               (map #(first (get by-id %)) (distinct (map :case-id cases)))))
        baseline-cases     (side-cases :baseline)
        candidate-cases    (side-cases :candidate)
        baseline-failures  (run-failures baseline-cases (:metadata baseline) (:termination baseline))
        candidate-failures (run-failures candidate-cases (:metadata candidate) (:termination candidate))]
    (str "Permissions " (pr-str permissions) "\n"
         (when edge
           (str "Edge " (:from edge) " -> " (:to edge) " feature " (:feature edge)
                (when (= (set (:baseline-features edge)) (set (:candidate-features edge)))
                  " (effective feature sets identical)")
                "\n  effective features " (pr-str (select-keys edge [:baseline-features :candidate-features :added :removed]))
                "\n"))
         (when (some #(get-in % [:effective-graphics :software])
                     (mapcat (fn [pair] (concat (get-in pair [:baseline :preparations])
                                                (get-in pair [:candidate :preparations]))) pairs))
           "SOFTWARE RENDERER recorded\n")
         "Baseline " (pr-str (:run-id baseline)) " Git " (pr-str (get-in baseline [:metadata :git]))
         "\nBaseline termination " (pr-str (dissoc (:termination baseline) :failure))
         "\nCandidate " (pr-str (:run-id candidate)) " Git " (pr-str (get-in candidate [:metadata :git]))
         "\nCandidate termination " (pr-str (dissoc (:termination candidate) :failure))
         "\nAnalysis " (pr-str analysis) "\n" source-coverage "\n" interpretation
         (failures->text "Baseline failures" baseline-failures)
         (screenshots->text "Baseline screenshots" (:metadata baseline) baseline-failures baseline-cases)
         (failures->text "Candidate failures" candidate-failures)
         (screenshots->text "Candidate screenshots" (:metadata candidate) candidate-failures candidate-cases)
         (apply str
                (map (fn [{:keys [baseline candidate mismatches metrics]}]
                       (str "\n\nPair " (result/case-name (:case-id baseline)) ","
                            (result/case-name (:case-id candidate))
                            (apply str
                                   (map (fn [mismatch]
                                          (str "\n" (if (:waived? mismatch) "ACKNOWLEDGED" "UNACKNOWLEDGED")
                                               " DIFFERENCE " (pr-str mismatch))) mismatches))
                            (side->text "Baseline" baseline)
                            (side->text "Candidate" candidate)
                            (str/join "\n"
                                      (map (fn [[id change]]
                                             (str "\n" (pr-str id)
                                                  " median change " (estimate->text (:absolute change))
                                                  " CI " (estimate->text (:absolute-ci change))
                                                  "; percent " (estimate->text (:percentage change))
                                                  " CI " (estimate->text (:percentage-ci change)))) metrics))))
                     pairs)))))
