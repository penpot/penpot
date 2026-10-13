;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns bench.component-sync
  "Benchmark of component sync on fixed-size worlds, with a recorded
   baseline to compare against.

   Each world is built once; only the work a user edit causes is timed: the
   sync of the changed components, then of the components each sync changed,
   until none is left (the frontend watcher does one pass per sync commit),
   with every change applied to the file.

   Run from `common/` with `scripts/bench-components` (`record` writes the
   baseline, `compare`, the default, checks against it)."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.logic.shapes :as cls]
   [app.common.logic.variants :as clv]
   [app.common.test-helpers.components :as thc]
   [app.common.test-helpers.composable.comp.runner :as r]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.variants :as thv]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [criterium.core :as cri]))

(def ^:private baseline-path "dev/bench/component_sync_baseline.edn")

;; A world slower than its baseline by more than this ratio is flagged.
(def ^:private tolerance 1.10)

(def ^:private bench-opts
  {:samples               20
   :target-execution-time (* 500 1000 1000)
   :warmup-jit-period     (* 3 1000 1000 1000)})

;; How long each world runs untimed before any is measured, in ms, so the
;; code they share is compiled before the first measurement.
(def ^:private warmup-ms 3000)

;; ---- Sync

(defn- apply-changes
  [file changes]
  (thf/apply-changes file changes :validate? false))

(defn- edit
  "`file` after `f` changed the shape `label` through the production path,
   and the ids of the components that changed."
  [file label f]
  (let [page    (thf/current-page file)
        changes (cls/generate-update-shapes (pcb/empty-changes nil (:id page))
                                            #{(thi/id label)}
                                            f
                                            (:objects page)
                                            {})]
    [(apply-changes file changes) (r/changed-components (:data file) changes)]))

(defn- sync-cascade
  "`[file passes]` after syncing the copies of `component-ids` in `file` to
   a fixpoint (see `r/sync-cascade`)."
  [file libraries component-ids]
  (let [{:keys [file passes]} (r/sync-cascade file libraries component-ids)]
    [file passes]))

(defn- red
  [shape]
  (assoc shape :fills (ths/sample-fills-color :fill-color "#ff0000")))

;; ---- Worlds
;;
;; A world is `{:key :describe :run :reds}`; `run` is the timed work, a
;; function of no arguments returning `[file passes]`, and `reds` how many
;; rects the resulting file must have red, so a world that synced nothing
;; fails instead of timing nothing. A world whose file uses libraries lists
;; them under `:libraries`.

(defn- red-rects
  [file]
  (->> (vals (:objects (thf/current-page file)))
       (filter #(and (= :rect (:type %))
                     (= "#ff0000" (-> % :fills first :fill-color))))
       (count)))

(defn- instantiate-many
  "`file` with `n` copies of the component `label`, labelled `prefix-<i>`."
  [file label prefix n & {:as params}]
  (reduce (fn [file i]
            (thc/instantiate-component file label (keyword (str prefix "-" i)) params))
          file
          (range n)))

(defn- nest
  "`file` with a new component `label`, whose main `root-label` holds a copy
   of the component `inner-label`."
  [file label root-label inner-label]
  (-> file
      (tho/add-frame root-label)
      (thc/instantiate-component inner-label (keyword (str (name root-label) "-inner"))
                                 :parent-label root-label)
      (thc/make-component label root-label)))

(defn- main-edit-into-copies
  "One main whose child changes, synced into `n` copies."
  [n]
  (let [[file ids] (-> (thf/sample-file :file1)
                       (tho/add-simple-component :c0 :root0 :child0)
                       (instantiate-many :c0 "copy" n)
                       (edit :child0 red))]
    {:key      (keyword (str "main-edit-into-" n "-copies"))
     :describe (str "a main child changes; " n " copies sync")
     :run      #(sync-cascade file {} ids)
     :reds     (inc n)}))

(defn- deepest-edit-through-three-levels
  "Components c1 ⊃ c0, c2 ⊃ c1, c3 ⊃ c2, 100 copies of c3; c0's child
   changes."
  []
  (let [[file ids] (-> (thf/sample-file :file1)
                       (tho/add-simple-component :c0 :root0 :child0)
                       (nest :c1 :root1 :c0)
                       (nest :c2 :root2 :c1)
                       (nest :c3 :root3 :c2)
                       (instantiate-many :c3 "copy" 100)
                       (edit :child0 red))]
    {:key      :deepest-edit-through-three-levels
     :describe "the deepest of 3 nesting levels changes; 100 copies of the top sync"
     :run      #(sync-cascade file {} ids)
     ;; the main, its images in the three outer mains, and the copies
     :reds     104}))

(defn- edit-through-a-chain-of-dependents
  "Components a, b ⊃ a, c ⊃ b, with 100 copies of each; a's child changes."
  []
  (let [[file ids] (-> (thf/sample-file :file1)
                       (tho/add-simple-component :a :root-a :child-a)
                       (nest :b :root-b :a)
                       (nest :c :root-c :b)
                       (instantiate-many :a "copy-a" 100)
                       (instantiate-many :b "copy-b" 100)
                       (instantiate-many :c "copy-c" 100)
                       (edit :child-a red))]
    {:key      :edit-through-a-chain-of-dependents
     :describe "a changes in the chain a ⊂ b ⊂ c; 100 copies of each sync"
     :run      #(sync-cascade file {} ids)
     ;; the main, its images in b and c, and the copies
     :reds     303}))

(defn- variant-switch-into-copies
  "A component whose main holds a copy of variant member v1, with 100 copies;
   the copy in the main switches to member v2."
  []
  (let [file    (-> (thf/sample-file :file1)
                    (thv/add-variant-with-child :vset :v1 :v1-root :v2 :v2-root :v1-child :v2-child
                                                :child2-params {:fills (ths/sample-fills-color :fill-color "#ff0000")})
                    (nest :t :root-t :v1)
                    (instantiate-many :t "copy" 100))
        page    (thf/current-page file)
        shape   (ths/get-shape file :root-t-inner)
        libs    {(:id file) file}
        target  (clv/find-switch-target libs shape 0 "Value2")
        switch  (fn []
                  (let [[_ changes _] (clv/generate-component-swap-in-place
                                       (pcb/empty-changes nil (:id page))
                                       page libs (:data file) shape (:id target) true)]
                    (sync-cascade (apply-changes file changes)
                                  {}
                                  (r/changed-components (:data file) changes))))]
    {:key      :variant-switch-into-copies
     :describe "a variant copy in a main switches; 100 copies of the main sync"
     :run      switch
     ;; member v2's rect, the switched copy in the main, and the copies
     :reds     102}))

(defn- library-update-into-copies
  "A library component with 500 copies in a consuming file; the library main
   changed and the file accepts the update."
  []
  (let [library     (-> (thf/sample-file :library :is-shared true)
                        (tho/add-simple-component :lc :lroot :lchild))
        file        (-> (thf/sample-file :file1)
                        (instantiate-many :lc "copy" 500 :library library))
        [library _] (edit library :lchild red)
        libraries   {(:id library) library (:id file) file}
        update      (fn []
                      (let [changes (cll/generate-sync-file-changes (pcb/empty-changes)
                                                                    nil
                                                                    :components
                                                                    (:id file)
                                                                    nil
                                                                    (:id library)
                                                                    libraries
                                                                    (:id file))]
                        [(apply-changes file changes) 1]))]
    {:key       :library-update-into-copies
     :describe  "a library main changed; a file with 500 copies updates"
     :run       update
     :reds      500
     :libraries [library]}))

(defn world-builders
  "One function of no arguments per world, building it. Each world builds
   its file with test labels, so the golden master builds one world at a
   time and reads its labels before the next one replaces them."
  []
  [#(main-edit-into-copies 10)
   #(main-edit-into-copies 100)
   #(main-edit-into-copies 1000)
   deepest-edit-through-three-levels
   edit-through-a-chain-of-dependents
   variant-switch-into-copies
   library-update-into-copies])

(defn- worlds
  []
  (mapv #(%) (world-builders)))

;; ---- Measuring

(defn- ms
  [seconds]
  (/ (Math/round (* seconds 1e6)) 1e3))

(defn- measure
  [{:keys [key describe run reds]}]
  (println (str "  " (name key) ": " describe))
  (let [[file passes] (run)
        _             (when-not (= reds (red-rects file))
                        (throw (ex-info (str (name key) " did not sync: " (red-rects file)
                                             " red rects, expected " reds)
                                        {:world key})))
        result        (cri/benchmark* run bench-opts)]
    [key {:mean-ms    (ms (first (:mean result)))
          :lower-q-ms (ms (first (:lower-q result)))
          :passes     passes}]))

(defn- warm-up!
  [worlds]
  (doseq [{:keys [run]} worlds]
    (let [end (+ (System/currentTimeMillis) warmup-ms)]
      (while (< (System/currentTimeMillis) end)
        (run)))))

(defn- run-all
  []
  (let [worlds (worlds)]
    (println "  warming up")
    (warm-up! worlds)
    (into (sorted-map) (map measure) worlds)))

(defn- sh-out
  [& args]
  (try
    (let [{:keys [exit out]} (apply shell/sh args)]
      (when (zero? exit) (str/trim out)))
    (catch Exception _ nil)))

(defn- machine
  []
  {:cpu   (some-> (sh-out "sh" "-c" "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2")
                  (str/trim))
   :cores (.availableProcessors (Runtime/getRuntime))
   :os    (str (System/getProperty "os.name") " " (System/getProperty "os.arch"))
   :jvm   (System/getProperty "java.version")})

(defn- record!
  [results]
  (let [baseline {:commit   (or (sh-out "git" "rev-parse" "--short" "HEAD") "unknown")
                  :date     (str (java.time.LocalDate/now))
                  :machine  (machine)
                  :results  results}]
    (with-open [w (io/writer baseline-path)]
      (binding [*out* w]
        (println ";; Component sync benchmark baseline, written by `scripts/bench-components record`.")
        (pp/pprint baseline)))
    (println "Baseline written to" baseline-path)
    true))

(defn- compare!
  "Print each world's time against the baseline. Returns false when a world
   is slower than its baseline by more than the tolerance. The lower
   quantile is compared: background load and GC pauses move it far less
   than the mean."
  [results]
  (let [{:keys [commit] recorded :machine :as baseline} (edn/read-string (slurp baseline-path))
        rows (for [[key {:keys [lower-q-ms mean-ms]}] results
                   :let [base  (get-in baseline [:results key :lower-q-ms])
                         ratio (when base (/ lower-q-ms base))]]
               {:world    (name key)
                :baseline base
                :now      lower-q-ms
                :mean     mean-ms
                :ratio    (some-> ratio (* 100) Math/round (/ 100.0))
                :flag     (cond (nil? ratio) "new"
                                (> ratio tolerance) "SLOWER"
                                :else "")})]
    (println "Baseline from commit" commit "on" (:cpu recorded))
    (when (not= recorded (machine))
      (println "Warning: the baseline was recorded on another machine or JVM; ratios are rough."))
    (println "Times are the lower quantile (2.5%) of each world, in ms.")
    (pp/print-table [:world :baseline :now :mean :ratio :flag] rows)
    (not-any? #(= "SLOWER" (:flag %)) rows)))

(defn -main
  [& [mode]]
  (println "Component sync benchmark")
  (let [results (run-all)
        ok?     (case (or mode "compare")
                  "record"  (record! results)
                  "compare" (compare! results))]
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
