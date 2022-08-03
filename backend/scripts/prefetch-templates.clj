#!/usr/bin/env bb

(require '[babashka.curl :as curl]
         '[babashka.fs :as fs])

(defn download-if-needed!
  [dest data]
  (doseq [{:keys [id file-uri] :as item} data]
    (let [file (fs/file dest id)
          rsp  (curl/get file-uri {:as :stream})]

      (when (not= 200 (:status rsp))
        (println (format "unable to download %s (uri: %s)" id file-uri))
        (System/exit -1))

      (when-not (fs/exists? (str file))
        (println (format "=> downloading %s" id))
        (with-open [output (io/output-stream file)]
          (io/copy (:body rsp) output))))))

(defn read-defs-file
  [path]
  (with-open [content (io/reader path)]
    (edn/read-string (slurp content))))

(let [[path dest] *command-line-args*]
  (when (or (nil? path)
            (nil? dest))
    (println "invalid arguments")
    (System/exit -1))

  (when-not (fs/exists? path)
    (println (format "file %s does not exists" path))
    (System/exit -1))

  (when-not (fs/exists? dest)
    (fs/create-dirs dest))

  (let [data (read-defs-file path)]
    (download-if-needed! dest data)))
