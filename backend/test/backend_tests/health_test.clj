;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.health-test
  (:require
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.health :as health]
   [app.http.debug :as debug]
   [app.srepl.main :as srepl]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)

(defn- restore-availability
  "The availability flag is a global atom shared by the whole test JVM, so
  every test leaves the instance online."
  [next]
  (try
    (next)
    (finally
      (health/set-online))))

(t/use-fixtures :each (th/serial th/database-reset restore-availability))

;; ----------------------------------------------------------------
;; Tests: app.health state
;; ----------------------------------------------------------------

(t/deftest instance-is-online-by-default
  (t/is (true? (health/online?))))

(t/deftest set-offline-and-set-online-toggle-the-flag
  (health/set-offline)
  (t/is (false? (health/online?)))

  (health/set-online)
  (t/is (true? (health/online?))))

;; ----------------------------------------------------------------
;; Tests: /readyz handler
;; ----------------------------------------------------------------

(t/deftest readyz-reports-ok-when-instance-is-online
  (let [response (debug/health-handler th/*system* {})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "OK" (::yres/body response)))))

(t/deftest readyz-reports-unavailable-when-instance-is-offline
  (health/set-offline)
  (let [response (debug/health-handler th/*system* {})]
    (t/is (= 503 (::yres/status response)))
    (t/is (= "OFFLINE" (::yres/body response)))))

(t/deftest readyz-does-not-query-the-database-when-instance-is-offline
  (health/set-offline)
  (with-redefs [db/exec-one! (fn [_ _]
                               (throw (ex-info "database unreachable" {})))]
    (let [response (debug/health-handler th/*system* {})]
      (t/is (= 503 (::yres/status response)))
      (t/is (= "OFFLINE" (::yres/body response))))))

;; ----------------------------------------------------------------
;; Tests: srepl helpers (the operator entry points)
;; ----------------------------------------------------------------

(t/deftest srepl-helpers-change-the-readyz-answer
  (srepl/set-offline)
  (let [drained (debug/health-handler th/*system* {})]
    (t/is (= 503 (::yres/status drained)))
    (t/is (= "OFFLINE" (::yres/body drained))))

  (srepl/set-online)
  (let [restored (debug/health-handler th/*system* {})]
    (t/is (= 200 (::yres/status restored)))
    (t/is (= "OK" (::yres/body restored)))))
