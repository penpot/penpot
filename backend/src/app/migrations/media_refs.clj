;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.migrations.media-refs
  "A media refs migration fixer script"
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pprint]
   [app.srepl.fixes.media-refs :refer [process-file]]
   [app.srepl.main :as srepl]
   [clojure.edn :as edn]))

(def ^:private required-services
  [:app.storage.s3/backend
   :app.storage.fs/backend
   :app.storage/storage
   :app.metrics/metrics
   :app.db/pool
   :app.worker/executor])

(defn -main
  [& [options]]
  (try
    (let [config-var (requiring-resolve 'app.main/system-config)
          start-var  (requiring-resolve 'app.main/start-custom)
          stop-var   (requiring-resolve 'app.main/stop)
          config     (select-keys @config-var required-services)]

      (start-var config)

      (let [options (if (string? options)
                      (ex/ignoring (edn/read-string options))
                      {})]

        (l/inf :hint "executing media-refs migration" :options options)
        (srepl/process-files! process-file options))

      (stop-var)
      (System/exit 0))
    (catch Throwable cause
      (ex/print-throwable cause)
      (flush)
      (System/exit -1))))


