;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.graph-ingest-test
  "The batch-export tier, `app.graph.ingest`, which survives the retirement of
  the Ladybug session sync."
  (:require
   [app.common.uuid :as uuid]
   [app.graph.ingest :as ingest]
   [clojure.test :as t]))

(t/deftest ingest-on-connection-requires-allocator
  ;; The allocator must outlive the connection, so ingest cannot make its own
  ;; inside the call — see `app.graph.arrow/with-allocator!`.
  (try
    (ingest/ingest-on-connection! nil nil (uuid/next))
    (t/is false "expected :missing-arrow-allocator")
    (catch clojure.lang.ExceptionInfo e
      (t/is (= :missing-arrow-allocator (:code (ex-data e)))))))
