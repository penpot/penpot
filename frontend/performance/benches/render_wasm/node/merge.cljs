;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.node.merge
  "Offline merge of two saved schema:run records."
  (:require
   ["fs" :as fs]
   [benches.render-wasm.node.log :as log]
   [benches.render-wasm.node.runs :as runs]
   [benches.render-wasm.result :as result]))

(defn execute
  "Reads and merges two saved run files from the caller's cwd, then writes a
  new run home. Inputs remain unchanged. Returns the merged record and home."
  [root base-path additional-path {:keys [output-dir]}]
  (let [base       (runs/get-record base-path)
        additional (runs/get-record additional-path)
        run-id     (str (random-uuid))
        merged     (result/merge-runs base additional {:run-id run-id})
        home       (runs/create-home {:root root
                                      :run-id run-id
                                      :output-dir output-dir})]
    (runs/write-record! home merged)
    (fs/appendFileSync (:log-path home)
                       (str "merge " (:run-id base) " + " (:run-id additional) "\n"))
    (log/result (str "Run " run-id))
    (log/result (str "Output " (:dir home)))
    {:run merged :home home}))
