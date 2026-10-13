;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.golden-master
  "Golden masters of the component code: the exact files a test produces,
   recorded once and compared on every run.

   A state is a list of files, normalized (`thn/normalize-file`) and
   written as sorted lines `path value`, with every id replaced by its test
   label, or by `:unlabelled/<n>`, numbered in tree order (pages in order,
   each from its root; components by label, each from its main), and each
   grid cell by `:cell/<shape>.<row>.<column>`. So the lines are the same
   whatever ids a run draws, and timestamps are left out.

   A group (a case, a benchmark world) is a list of variants, each
   `{:transcript :states [lines ...]}`. An EDN file keeps a short digest
   per state; the full lines go to a directory outside git: `recorded/`
   when recording, `actual/` for each state that differs, so
   `diff -r <dir>/recorded <dir>/actual` shows what changed."
  (:require
   #?(:clj [clojure.edn :as edn])
   #?(:clj [clojure.java.io :as io])
   #?(:cljs [cljs.reader :as reader])
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.normalize :as thn]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.walk :as walk])
  #?(:clj
     (:import
      java.security.MessageDigest)))

#?(:cljs
   (defn- node-module
     "The Node built-in module `name`, in ESM and CommonJS builds alike."
     [name]
     (.getBuiltinModule js/process (str "node:" name))))

;; ---- Names

(defn- tree-ids
  "The ids of `root-id` and of the shapes under it in `objects`, depth first
   in `:shapes` order."
  [objects root-id]
  (loop [stack [root-id]
         ids   []]
    (if (empty? stack)
      ids
      (let [id (peek stack)]
        (recur (into (pop stack) (reverse (get-in objects [id :shapes])))
               (conj ids id))))))

(defn- label-key
  [label]
  (if (keyword? label) (str label) (pr-str label)))

(defn- test-labels
  "The test label of each labelled id; an id with several labels takes the
   first one in name order."
  []
  (reduce-kv (fn [labels label id]
               (let [current (get labels id)]
                 (if (and current (neg? (compare (label-key current) (label-key label))))
                   labels
                   (assoc labels id label))))
             {}
             (thi/labels)))

(defn- sorted-components
  [file labels]
  (->> (vals (get-in file [:data :components]))
       (sort-by (fn [{:keys [id name path]}]
                  [(some-> (get labels id) label-key) path name]))))

(defn- ordered-shapes
  "The shapes of `file` in a fixed order: each page's from its root, then,
   for each deleted component, its shapes from its main."
  [file labels]
  (let [data (:data file)]
    (concat
     (mapcat (fn [page-id]
               (let [objects (get-in data [:pages-index page-id :objects])]
                 (keep objects (tree-ids objects uuid/zero))))
             (:pages data))
     (mapcat (fn [{:keys [main-instance-id objects]}]
               (when objects
                 (keep objects (tree-ids objects main-instance-id))))
             (sorted-components file labels)))))

(defn- file-ids
  "The ids of `file` in a fixed order: the file, its pages, its shapes (see
   `ordered-shapes`), then each component and its main."
  [file labels]
  (concat
   [(:id file)]
   (get-in file [:data :pages])
   (map :id (ordered-shapes file labels))
   (mapcat (juxt :id :main-instance-id) (sorted-components file labels))))

(defn- cell-names
  "A name for each grid cell of the shapes in `files`, after the first shape
   that holds it in the order of `ordered-shapes` (copies share their main's
   cell ids): `:cell/<shape>.<row>.<column>`."
  [files labels names]
  (reduce (fn [cells {:keys [id layout-grid-cells]}]
            (reduce (fn [cells {cell-id :id :keys [row column]}]
                      (cond-> cells
                        (not (contains? cells cell-id))
                        (assoc cell-id (keyword "cell" (str (name (get names id)) "." row "." column)))))
                    cells
                    (sort-by (juxt :row :column) (vals layout-grid-cells))))
          {}
          (mapcat #(ordered-shapes % labels) files)))

(defn id-names
  "A name for every id in `files`: its test label, else `:unlabelled/<n>` in
   the order `file-ids` gives, and a name for each grid cell."
  [files]
  (let [labels (test-labels)
        names  (reduce (fn [names id]
                         (cond
                           (contains? names id) names
                           (contains? labels id) (assoc names id (get labels id))
                           :else (assoc names id (keyword "unlabelled"
                                                          (str (count (remove labels (keys names))))))))
                       {}
                       (mapcat #(file-ids % labels) files))]
    (merge (cell-names files labels names) names)))

(defn- rename-ids
  "`value` with every uuid named by `names` and every timestamp `:inst`; an
   id they miss (one that no tree reaches, such as a reference to a deleted
   shape) is `:unreached`."
  [value names]
  (walk/postwalk #(cond
                    (uuid? %) (get names % :unreached)
                    (ct/inst? %) :inst
                    :else %)
                 value))

(def ^:private uuid-re
  #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

(defn- rename-printed-ids
  "The string `s` with each id printed in it named by `names`."
  [s names]
  (str/replace s uuid-re #(name (get names (uuid/uuid %) :unreached))))

(defn transcript
  "The operations of `situation`, with each id named by `names` and each
   function printed as `#fn`."
  [situation names]
  (-> (tm/describe-applied situation)
      (rename-printed-ids names)
      (str/replace #"#uuid \"([^\"]+)\"" "$1")
      (str/replace #"#object\[[^\]]*\]" "#fn")))

;; ---- Lines

(defn- leaf?
  [value]
  (not (and (coll? value) (seq value))))

(defn- entries
  "The children of a collection as `[key value]` pairs: a map's entries in
   key order, a set's elements in printed order, a sequence's by index."
  [value]
  (cond
    (map? value) (sort-by (comp pr-str key) value)
    (set? value) (map-indexed vector (sort-by pr-str value))
    :else        (map-indexed vector value)))

(defn- lines
  "The lines `path value` of `value`, one per leaf. An id printed inside a
   leaf (a token theme's id in the tokens library) is named by `names`."
  [value names]
  (let [out (volatile! (transient []))]
    (letfn [(walk [path value]
              (if (leaf? value)
                (vswap! out conj! (str (pr-str path) " " (rename-printed-ids (pr-str value) names)))
                (doseq [[k v] (entries value)]
                  (walk (conj path k) v))))]
      (walk [] value))
    (persistent! @out)))

(defn state-lines
  "The lines of a state: `files` normalized with their ids named, and
   `extra`, named too. `opts` go to `thn/normalize-file`."
  ([files extra] (state-lines files extra {}))
  ([files extra opts]
   (let [names (id-names files)]
     (lines (rename-ids {:files (into {} (map (fn [file]
                                                [(get names (:id file)) (thn/normalize-file file opts)]))
                                      files)
                         :extra extra}
                        names)
            names))))

(defn digest
  "A short digest of `lines`."
  [lines]
  (let [text (str (str/join "\n" lines) "\n")]
    #?(:clj
       (let [md (MessageDigest/getInstance "SHA-256")]
         (subs (apply str (map #(format "%02x" %) (.digest md (.getBytes ^String text "UTF-8"))))
               0 16))
       :cljs
       (let [crypto (node-module "crypto")]
         (subs (-> (.createHash crypto "sha256") (.update text "utf8") (.digest "hex")) 0 16)))))

;; ---- Files

(defn- state-path
  [dir kind group variant state]
  (let [pad (fn [n width] (let [s (str n)] (str (apply str (repeat (- width (count s)) "0")) s)))]
    (str dir "/" kind "/" (name group) "/" (pad variant 3) "-" (pad state 2) ".txt")))

(defn- write-text!
  [path text]
  #?(:clj
     (let [file (io/file path)]
       (io/make-parents file)
       (spit file text))
     :cljs
     (let [fs        (node-module "fs")
           node-path (node-module "path")]
       (.mkdirSync fs (.dirname node-path path) #js {:recursive true})
       (.writeFileSync fs path text))))

(defn- write-lines!
  [path lines]
  (write-text! path (str (str/join "\n" lines) "\n")))

(defn read-digests
  "The digests recorded in the EDN file at `path`, or nil when there is
   none."
  [path]
  #?(:clj
     (let [file (io/file path)]
       (when (.exists file)
         (edn/read-string (slurp file))))
     :cljs
     (let [fs (node-module "fs")]
       (when (.existsSync fs path)
         (reader/read-string (.readFileSync fs path "utf8"))))))

(defn write-digests!
  "Write `digests`, a map of group keys to variants' digests, to the EDN
   file at `path`."
  [path digests]
  (write-text! path (str ";; Golden master digests, written by the golden master test.\n"
                         "{"
                         (str/join "\n " (for [[group variants] (sort-by (comp str key) digests)]
                                           (str (pr-str group) "\n ["
                                                (str/join "\n  " (map pr-str variants))
                                                "]")))
                         "}\n")))

(defn- variant-digests
  [{:keys [transcript states]}]
  {:transcript transcript
   :states     (mapv digest states)})

(defn record-group!
  "Write the lines of each state of `variants` under `<dir>/recorded/` and
   return their digests."
  [dir group variants]
  (doseq [[i {:keys [transcript states]}] (map-indexed vector variants)
          [j lines] (map-indexed vector states)]
    (write-lines! (state-path dir "recorded" group i j) (cons transcript lines)))
  (mapv variant-digests variants))

(defn compare-group!
  "Assert that `variants` match `recorded`, the digests of `group`, writing
   the lines of each state that differs under `<dir>/actual/`."
  [dir group variants recorded]
  (t/testing (str "golden master of " (name group))
    (t/is (some? recorded) "the group has no recorded golden master")
    (t/is (= (count recorded) (count variants)) "the number of variants changed")
    (doseq [[i variant expected] (map vector (range) variants recorded)
            :let [{:keys [transcript states]} variant
                  actual (variant-digests variant)]]
      (t/testing (str "variant " i ":\n  " transcript)
        (t/is (= (:transcript expected) transcript) "the variant's operations changed")
        (t/is (= (count (:states expected)) (count states)) "the number of states changed")
        (doseq [[j lines want got] (map vector (range) states (:states expected) (:states actual))]
          (let [path (state-path dir "actual" group i j)]
            (when (not= want got)
              (write-lines! path (cons transcript lines)))
            (t/is (= want got) (str "state " j " differs: " path))))))))
