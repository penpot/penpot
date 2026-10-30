;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.files
  "Filesystem paths and storage for benchmark artifacts."
  (:require
   ["fs" :as fs]
   ["path" :as path]
   [clojure.string :as str]))

(defn valid-id?
  "True for IDs of up to 128 letters, digits, underscores or hyphens,
  starting with a letter or digit."
  [id]
  (and (string? id)
       (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9_-]{0,127}" id))))

(defn contained?
  "True when `file` is `root` or a path below it."
  [root file]
  (let [relative (path/relative root file)]
    (and (not (path/isAbsolute relative))
         (not= relative "..")
         (not (str/starts-with? relative (str ".." path/sep))))))

(defn create-home
  "Creates `dir` and its parents, reserves an empty log, and returns the
  directory, log path and record path. `stem` names the .log and .transit files."
  [dir stem]
  (let [log-path (path/join dir (str stem ".log"))]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync log-path "" #js {:encoding "utf8" :flag "wx"})
    {:dir dir
     :log-path log-path
     :record-path (path/join dir (str stem ".transit"))}))

(defn get-real-path
  "Resolves symlink ancestors even when the final path does not exist yet."
  [file]
  (let [file (path/resolve file)]
    (if (fs/existsSync file)
      (fs/realpathSync file)
      (path/join (get-real-path (path/dirname file)) (path/basename file)))))

(defn atomic-write
  "Writes (UTF-8) content to a temporary file and renames it over `file`,
  so readers see either the previous complete file or the new one. The parent
  directory must exist.

  A failed write or rename leaves the previous file untouched and removes the
  temporary file."
  [file content]
  (let [temporary (str file "." (random-uuid) ".tmp")]
    (try
      (fs/writeFileSync temporary content #js {:encoding "utf8" :flag "wx"})
      (fs/renameSync temporary file)
      (finally
        (fs/rmSync temporary #js {:force true})))))
