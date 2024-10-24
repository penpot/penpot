;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.svgo
  "A SVG Optimizer service"
  (:require
   [app.common.logging :as l]
   [app.util.shell :as shell]
   [datoteka.fs :as fs]
   [promesa.exec.semaphore :as ps]))

(def ^:dynamic *semaphore*
  "A dynamic variable that can optionally contain a traffic light to
  appropriately delimit the use of resources, managed externally."
  nil)

(set! *warn-on-reflection* true)

(defn optimize
  [system data]
  (try
    (some-> *semaphore* ps/acquire!)
    (let [script (fs/join fs/*cwd* "scripts/svgo-cli.js")
          cmd    ["node" (str script)]
          result (shell/exec! system
                              :cmd cmd
                              :in data)]
      (if (= (:exit result) 0)
        (:out result)
        (do
          (l/raw! :warn (str "Error on optimizing svg, returning svg as-is." (:err result)))
          data)))

    (finally
      (some-> *semaphore* ps/release!))))
