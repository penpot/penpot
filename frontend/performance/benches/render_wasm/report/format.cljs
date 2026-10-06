;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.report.format
  "Text formatting for run summaries and saved comparisons.

  Text leads with observed median, bootstrap interval and distinct counts.
  Full statistics and forced differences retain units and observation bases."
  (:require
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
  [{:keys [valid invalid absent failed unattempted warmup]}]
  (str "valid=" valid " invalid=" invalid " absent=" absent " failed=" failed
       " unattempted=" unattempted " warmup=" warmup))

(defn metric->text
  "Formats all statistics for one metric, including batch basis and support limits."
  [id {:keys [definitions counts statistics]}]
  (str (pr-str id) " median " (estimate->text (or (:median statistics) statistics))
       " CI " (estimate->text (or (:median-ci statistics) statistics)) " | " (counts->text counts)
       "\n  definitions " (pr-str definitions)
       (if-let [reason (:reason statistics)]
         (str "\n  unavailable (" (name reason) ")")
         (str "\n  mean " (estimate->text (:mean statistics))
              " min " (estimate->text (:min statistics)) " max " (estimate->text (:max statistics))
              " sample SD " (estimate->text (:sample-sd statistics))
              " MAD " (estimate->text (:mad statistics))
              " p95 " (estimate->text (:p95 statistics)) " p99 " (estimate->text (:p99 statistics))))))

(defn format-run
  "Formats a summary without browser, registry or renderer access."
  [{:keys [run-id metadata analysis termination source-coverage interpretation cases]}]
  (str "Run " (pr-str run-id) " " (pr-str termination)
       "\nGit " (pr-str (:git metadata))
       "\nAnalysis " (pr-str analysis)
       "\n" source-coverage "\n" interpretation
       (when (some #(get-in % [:effective-graphics :software])
                   (mapcat :preparations cases)) "\nSOFTWARE RENDERER recorded")
       (apply str (map (fn [{:keys [case-id remaining metrics]}]
                         (str "\n\nCase " (result/case-name case-id)
                              " remaining " (pr-str remaining)
                              (apply str (map (fn [[id metric]] (str "\n" (metric->text id metric))) metrics))))
                       cases))))

(defn- side->text
  "Displays one comparison side's definitions, preparation facts and observations."
  [label {:keys [case-id definition preparations metrics]}]
  (str "\n" label " " (result/case-name case-id)
       "\n  case " (pr-str definition)
       "\n  preparations " (pr-str preparations)
       (apply str (map (fn [[id metric]] (str "\n" (metric->text id metric))) metrics))))

(defn format-comparison
  "Formats changes, exploratory intervals and every recorded mismatch.
  Forced output displays both saved definitions and original metric definitions."
  [{:keys [baseline candidate analysis forced? source-coverage interpretation pairs]}]
  (str (when forced? "FORCED COMPARISON: compatibility differences bypassed\n")
       (when (some #(get-in % [:effective-graphics :software])
                   (mapcat (fn [pair] (concat (get-in pair [:baseline :preparations])
                                              (get-in pair [:candidate :preparations]))) pairs))
         "SOFTWARE RENDERER recorded\n")
       "Baseline " (pr-str (:run-id baseline)) " Git " (pr-str (get-in baseline [:metadata :git]))
       "\nCandidate " (pr-str (:run-id candidate)) " Git " (pr-str (get-in candidate [:metadata :git]))
       "\nAnalysis " (pr-str analysis) "\n" source-coverage "\n" interpretation
       (apply str
              (map (fn [{:keys [baseline candidate mismatches metrics]}]
                     (str "\n\nPair " (result/case-name (:case-id baseline)) ","
                          (result/case-name (:case-id candidate))
                          (when (seq mismatches) (str "\nMISMATCHES " (pr-str mismatches)))
                          (side->text "Baseline" baseline)
                          (side->text "Candidate" candidate)
                          (str/join "\n"
                                    (map (fn [[id change]]
                                           (str "\n" (pr-str id)
                                                " median change " (estimate->text (:absolute change))
                                                " CI " (estimate->text (:absolute-ci change))
                                                "; percent " (estimate->text (:percentage change))
                                                " CI " (estimate->text (:percentage-ci change)))) metrics))))
                   pairs))))
