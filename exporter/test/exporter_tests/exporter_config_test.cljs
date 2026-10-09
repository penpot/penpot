;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.exporter-config-test
  "The process configuration, read from the environment: kebab-case
  keys under the `PENPOT_` prefix, decoded and validated against the
  schema, with the management key derived the way the backend expects."
  (:require
   [cljs.test :as t :include-macros true]
   [exporter.config :as cf]))

(t/deftest read-env-keeps-only-the-prefixed-keys
  (let [env (cf/read-env #js {"PENPOT_SECRET_KEY" "s"
                              "PENPOT_EXPORTER_WORKER_CONCURRENCY" "4"
                              "OTHER_THING" "y"})]
    (t/is (= {:secret-key "s"
              :exporter-worker-concurrency "4"}
             env))))

(t/deftest prepare-config-applies-defaults-and-coerces
  (let [config (cf/prepare-config #js {"PENPOT_SECRET_KEY" "s"
                                       "PENPOT_EXPORTER_WORKER_CONCURRENCY" "4"})]
    (t/is (= "s" (:secret-key config)))
    (t/is (= "default" (:tenant config)))
    (t/is (= "/tmp/penpot" (:tempdir config)))
    (t/is (= 4 (:exporter-worker-concurrency config)))))

(t/deftest prepare-config-refuses-a-missing-secret
  ;; the secret is the one key without a default: without it the
  ;; process cannot derive its management key, so the config is
  ;; invalid. It throws (testable) instead of exiting: only the
  ;; wiring entry exits, on the live environment.
  (let [cause (try
                (cf/prepare-config #js {})
                nil
                (catch :default cause
                  cause))]
    (t/is (some? cause))
    (t/is (= :invalid-config (:code (ex-data cause))))))

(t/deftest derive-management-key-matches-the-legacy-formula
  ;; the backend derives the same key from the same secret (HKDF
  ;; blake2b512 over "exporter", 32 bytes, base64url): the hardcoded
  ;; value pins the formula, so a refactor cannot silently mint a key
  ;; the backend rejects.
  (t/is (= "ASwdQtEgXamXWme1LXZTcH5lvKR5WEbIHrf1i5mDWa4"
           (cf/derive-management-key "test-secret-key"))))
