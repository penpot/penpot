#!/usr/bin/env bb

;; ── Dependencies (resolved once, cached forever by Babashka) ──
(babashka.deps/add-deps
  '{:deps {dev.weavejester/cljfmt {:mvn/version "0.15.5"}
           parinferish/parinferish {:mvn/version "0.8.0"}}})

(ns paren-repair
  "Standalone CLI tool for fixing delimiter errors and formatting Clojure files.

   Single-file consolidation of:
     clojure-mcp-light.delimiter-repair  (detection + repair engine)
     clojure-mcp-light.hook              (file detection + combine repair+format)
     clojure-mcp-light.paren-repair      (CLI / main entry point)

   Stripped: stats logging, timbre, tools.cli, apply-patch, tmp sessions.
   Includes a fix for the process-stdin destructuring bug in the original."

  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [cljfmt.core :as cljfmt]
            [cljfmt.main]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [edamame.core :as e]
            [parinferish.core :as parinferish]))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Section 1: Delimiter Detection & Repair
;; (from delimiter_repair.clj — stats calls removed)
;; ═══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *signal-on-bad-parse*
  "When true, non-delimiter parse errors still trigger parinfer as a safety net.
   Running parinfer on valid code is generally benign."
  true)

(defn delimiter-error?
  "Returns true if the string has a delimiter error specifically.
   Checks that it's an :edamame/error with :edamame/opened-delimiter info.
   Uses :all true to enable all standard Clojure reader features:
   function literals, regex, quotes, syntax-quote, deref, var, etc.
   Also enables :read-cond :allow to support reader conditionals.
   Handles unknown data readers gracefully with a default reader fn."
  [s]
  (try
    (e/parse-string-all s {:all true
                           :features #{:bb :clj :cljs :cljr :default}
                           :read-cond :allow
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    false ;; No error = no delimiter error
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))
    (catch Exception _
      ;; Non-edamame parse error — run parinfer as a safety net
      ;; (parinfer on valid code is generally benign)
      *signal-on-bad-parse*)))

(defn actual-delimiter-error?
  "Like delimiter-error? but never falls back to parinfer on unknown parse errors."
  [s]
  (binding [*signal-on-bad-parse* false]
    (delimiter-error? s)))

(defn parinferish-repair
  "Attempts to repair delimiter errors using parinferish (pure Clojure).
   Returns a map with :success, :text, and :error."
  [s]
  (try
    (let [repaired (parinferish/flatten
                    (parinferish/parse s {:mode :indent}))]
      {:success true
       :text repaired
       :error nil})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

(def parinfer-rust-available?
  "Check if parinfer-rust binary is available on PATH. Result is memoized."
  (memoize
   (fn []
     (try
       (let [result (shell/sh "which" "parinfer-rust")]
         (zero? (:exit result)))
       (catch Exception _
         false)))))

(defn parinfer-repair
  "Attempts to repair delimiter errors using parinfer-rust (external binary).
   Returns a map with :success, :text, and :error.
   Uses JSON output format from parinfer-rust."
  [s]
  (let [result (shell/sh "parinfer-rust"
                         "--mode" "indent"
                         "--language" "clojure"
                         "--output-format" "json"
                         :in s)
        exit-code (:exit result)]
    (if (zero? exit-code)
      (try
        (json/parse-string (:out result) true)
        (catch Exception _
          {:success false}))
      {:success false})))

(defn repair-delimiters
  "Unified delimiter repair: prefers parinfer-rust when available,
   falls back to parinferish (pure Clojure).
   Returns a map with :success, :text, and :error."
  [s]
  (if (parinfer-rust-available?)
    (parinfer-repair s)
    (parinferish-repair s)))

(defn fix-delimiters
  "Takes a Clojure string and attempts to fix delimiter errors.
   Returns the repaired string if successful, or nil if unfixable.
   If no delimiter errors exist, returns the original string unchanged."
  [s]
  (if (delimiter-error? s)
    (let [{:keys [text success]} (repair-delimiters s)]
      (when (and success text (not (delimiter-error? text)))
        text))
    s))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Section 2: File Processing
;; (from hook.clj — stripped of stats, timbre, backup/restore, hook dispatch)
;; ═══════════════════════════════════════════════════════════════════════════════

(def ^:dynamic *enable-cljfmt*
  "When true, files are reformatted with cljfmt after delimiter repair."
  false)

(defn- babashka-shebang?
  "Checks if a file starts with a Babashka shebang line."
  [file-path]
  (when (fs/exists? file-path)
    (try
      (with-open [r (io/reader file-path)]
        (let [line (-> r line-seq first)]
          (and line
               (re-matches #"^#!/[^\s]+/(bb|env\s{1,3}bb)(\s.*)?$" line))))
      (catch Exception _ false))))

(defn clojure-file?
  "Checks if a file path has a Clojure-related extension or Babashka shebang.
   Supported extensions: .clj .cljs .cljc .cljd .bb .edn .lpy
   Also detects files with a Babashka shebang (#!/.../bb)."
  [file-path]
  (when file-path
    (let [lower-path (string/lower-case file-path)]
      (or (string/ends-with? lower-path ".clj")
          (string/ends-with? lower-path ".cljs")
          (string/ends-with? lower-path ".cljc")
          (string/ends-with? lower-path ".cljd")
          (string/ends-with? lower-path ".bb")
          (string/ends-with? lower-path ".lpy")
          (string/ends-with? lower-path ".edn")
          (babashka-shebang? file-path)))))

(defn run-cljfmt
  "Check if file needs formatting (via cljfmt.core), then reformat in-place
   (via cljfmt.main to respect user cljfmt config). Returns true if file was
   reformatted, false otherwise."
  [file-path]
  (when *enable-cljfmt*
    (try
      (let [original (slurp file-path :encoding "UTF-8")
            formatted (cljfmt/reformat-string original)]
        (if (not= original formatted)
          (do
            (cljfmt.main/-main "fix" file-path)
            true)
          false))
      (catch Exception _
        false))))

(defn fix-and-format-file!
  "Core logic for fixing delimiters and (optionally) formatting a Clojure file
   in-place. Returns a map with :success, :delimiter-fixed, :formatted, and
   :message."
  [file-path enable-cljfmt]
  (try
    (let [file-content (slurp file-path :encoding "UTF-8")
          has-delimiter-error? (delimiter-error? file-content)]
      (if has-delimiter-error?
        ;; Has delimiter error — try to fix
        (if-let [fixed-content (fix-delimiters file-content)]
          (do
            (spit file-path fixed-content :encoding "UTF-8")
            (let [formatted? (binding [*enable-cljfmt* enable-cljfmt]
                               (run-cljfmt file-path))]
              {:success true
               :delimiter-fixed true
               :formatted (boolean formatted?)
               :message "Delimiter errors fixed and formatted"}))
          {:success false
           :delimiter-fixed false
           :formatted false
           :message "Could not fix delimiter errors"})
        ;; No delimiter error — just format if enabled
        (let [formatted? (binding [*enable-cljfmt* enable-cljfmt]
                           (run-cljfmt file-path))]
          {:success true
           :delimiter-fixed false
           :formatted (boolean formatted?)
           :message (if formatted? "Formatted" "No changes needed")})))
    (catch Exception e
      {:success false
       :delimiter-fixed false
       :formatted false
       :message (str "Error: " (.getMessage e))})))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Section 3: CLI
;; (from paren_repair.clj — with process-stdin bug fix, no timbre)
;; ═══════════════════════════════════════════════════════════════════════════════

(defn has-stdin-data?
  "Check if stdin has data available (not a TTY).
   Returns true if stdin is ready to be read (e.g., piped input or heredoc)."
  []
  (try
    (.ready *in*)
    (catch Exception _ false)))

(defn process-stdin
  "Process code from stdin: fix delimiters and format.
   Outputs result to stdout.
   Returns a map with :success and :changed."
  []
  (let [input (slurp *in*)
        fixed (fix-delimiters input)]
    (if fixed
      ;; fix-delimiters succeeded (or no errors) — format and print
      (let [formatted (try
                        (cljfmt/reformat-string fixed)
                        (catch Exception _
                          fixed))
            changed? (not= input formatted)]
        (print formatted)
        (flush)
        {:success true
         :changed changed?})
      ;; fix-delimiters returned nil (unfixable)
      (do
        (binding [*out* *err*]
          (println "Error: Could not fix delimiter errors"))
        {:success false
         :changed false}))))

(defn process-file
  "Process a single file: fix delimiters and format in-place.
   Returns a map with :success, :file-path, :message, :delimiter-fixed,
   and :formatted."
  [file-path]
  (cond
    (not (fs/exists? file-path))
    {:success false
     :file-path file-path
     :message "File does not exist"
     :delimiter-fixed false
     :formatted false}

    (not (clojure-file? file-path))
    {:success false
     :file-path file-path
     :message "Not a Clojure file (skipping)"
     :delimiter-fixed false
     :formatted false}

    :else
    (assoc (fix-and-format-file! file-path true)
           :file-path file-path)))

(defn show-help []
  (println "Usage: paren-repair [FILE ...]")
  (println "       echo CODE | paren-repair")
  (println "       paren-repair <<'EOF' ... EOF")
  (println)
  (println "Fix delimiter errors and format Clojure code.")
  (println)
  (println "When no files are provided, reads from stdin and writes to stdout.")
  (println "If no changes are needed, echoes the input unchanged.")
  (println)
  (println "Options:")
  (println "  -h, --help    Show this help message"))

(defn -main [& args]
  (let [show-help? (some #{"--help" "-h"} args)
        file-args (remove #{"--help" "-h"} args)]

    (cond
      ;; Help requested
      show-help?
      (do
        (show-help)
        (System/exit 0))

      ;; No file args — check for stdin
      (empty? file-args)
      (if (has-stdin-data?)
        ;; Stdin mode: read, process, output to stdout
        (let [result (process-stdin)]
          (System/exit (if (:success result) 0 1)))
        ;; No stdin and no files — show help
        (do
          (show-help)
          (System/exit 1)))

      ;; File mode
      :else
      (try
        (let [results (doall (map process-file file-args))
              successes (filter :success results)
              failures (filter (complement :success) results)
              success-count (count successes)
              failure-count (count failures)]

          ;; Print results
          (println)
          (println "paren-repair Results")
          (println "========================")
          (println)

          (doseq [{:keys [file-path message delimiter-fixed formatted]} results]
            (let [tags (when (or delimiter-fixed formatted)
                         (str " ["
                              (string/join ", "
                                           (filter some?
                                                   [(when delimiter-fixed "delimiter-fixed")
                                                    (when formatted "formatted")]))
                              "]"))]
              (println (str "  " file-path ": " message tags))))

          (println)
          (println "Summary:")
          (println "  Success:" success-count)
          (println "  Failed: " failure-count)
          (println)

          (if (zero? failure-count)
            (System/exit 0)
            (System/exit 1)))
        (catch Exception e
          (binding [*out* *err*]
            (println "Fatal error:" (.getMessage e)))
          (System/exit 1))))))

;; ═══════════════════════════════════════════════════════════════════════════════
;; Entry point — only run -main when executed directly (not loaded as lib)
;; ═══════════════════════════════════════════════════════════════════════════════

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
