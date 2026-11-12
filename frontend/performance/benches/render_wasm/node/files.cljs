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
   [benches.render-wasm.errors :as errors :refer-macros [attempt]]
   [clojure.string :as str]))

(defn contained?
  "True when `file` is `root` or a path below it."
  [root file]
  (let [relative (path/relative root file)]
    (and (not (path/isAbsolute relative))
         (not= relative "..")
         (not (str/starts-with? relative (str ".." path/sep))))))

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

  A failed write or rename leaves the previous file untouched and attempts to
  remove the temporary file. Failures retain operation context in order."
  [file content]
  (let [temporary (str file "." (random-uuid) ".tmp")
        written   (attempt :catch-fatal
                           (fs/writeFileSync temporary content #js {:encoding "utf8" :flag "wx"})
                           nil)
        renamed   (when-not written
                    (attempt :catch-fatal (fs/renameSync temporary file) nil))
        removed   (when (or written renamed)
                    (attempt :catch-fatal (fs/rmSync temporary #js {:force true}) nil))]
    (when-let [failure (errors/combine
                        [{:phase "storage" :operation :write-temporary :path file :error written}
                         {:phase "storage" :operation :rename-temporary :path file :error renamed}
                         {:phase "storage" :operation :remove-temporary :path temporary :error removed}])]
      (throw failure))))
