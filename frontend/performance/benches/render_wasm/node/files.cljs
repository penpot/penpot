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
   [benches.render-wasm.errors :as errors]
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

(defn invalid-home
  "Throws an ::invalid-home exception with `data` and an optional cause."
  ([message data]
   (throw (ex-info message (assoc data :type ::invalid-home))))
  ([message data cause]
   (throw (ex-info message (assoc data :type ::invalid-home) cause))))

(defn check-id
  "Returns `id` when it is valid, or throws ::invalid-home with `message`."
  [id message]
  (when-not (valid-id? id)
    (invalid-home message {:id id}))
  id)

(defn check-output-dir
  "Resolves an explicit user directory from the caller's cwd and checks that it
  does not exist. Returns nil for nil. Does not create anything."
  [dir]
  (when (some? dir)
    (when (str/blank? dir)
      (invalid-home "output directory requires a nonempty path" {}))
    (let [resolved (path/resolve dir)
          exists?  (try
                     (fs/lstatSync resolved #js {:throwIfNoEntry false})
                     (catch :default cause
                       (let [detail (errors/message-of cause)]
                         (invalid-home (str "cannot inspect output directory: " detail)
                                       {:path resolved :cause detail}
                                       cause))))]
      (when exists?
        (invalid-home "output directory already exists" {:path resolved}))
      resolved)))

(defn- create-home*
  [dir stem]
  (let [log-path (path/join dir (str stem ".log"))]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync log-path "" #js {:encoding "utf8" :flag "wx"})
    {:dir dir
     :log-path log-path
     :record-path (path/join dir (str stem ".transit"))}))

(defn create-home
  "Creates `dir` and its parents, reserves an empty log.

  A filesystem failure throws ::invalid-home with `message` and `:path`, and
  keeps the original cause.

  Args:
      dir: path to create.
      stem: names the .log and .transit files.
      message: used for errors

  Returns:
      Directory created, log path and record path."
  [dir stem message]
  (try
    (create-home* dir stem)
    (catch :default cause
      (invalid-home message
                    {:path dir :cause (errors/message-of cause)}
                    cause))))

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
