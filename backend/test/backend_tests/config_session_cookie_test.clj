;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.config-session-cookie-test
  "Auth-token cookie max-age / renewal-max-age: defaults, env override (merge), session renewal threshold."
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.http.session :as session]
   [clojure.test :as t]
   [environ.core :refer [env]]))

(t/deftest default-map-includes-unified-session-durations
  (let [max-age (:auth-token-cookie-max-age cf/default)
        renewal (:auth-token-cookie-renewal-max-age cf/default)]
    (t/is (ct/duration? max-age))
    (t/is (ct/duration? renewal))
    (t/is (= (ct/duration {:days 7}) max-age))
    (t/is (= (ct/duration {:hours 1}) renewal))))

(t/deftest read-config-uses-defaults-when-env-prefix-has-no-keys
  ;; No process env uses this prefix; read-env is empty → merged config is `default` only.
  (let [cfg (cf/read-config :prefix "penpotzzzzunused"
                            :default cf/default)]
    (t/is (= (ct/duration {:days 7}) (:auth-token-cookie-max-age cfg)))
    (t/is (= (ct/duration {:hours 1}) (:auth-token-cookie-renewal-max-age cfg)))))

(t/deftest read-config-env-overrides-default
  (let [extra {:penpot-auth-token-cookie-max-age "172800s"
               :penpot-auth-token-cookie-renewal-max-age "7200s"}
        merged (merge env extra)]
    (with-redefs [env merged]
      (let [cfg (cf/read-config :default cf/default)]
        (t/is (= (ct/duration {:days 2}) (:auth-token-cookie-max-age cfg)))
        (t/is (= (ct/duration {:hours 2}) (:auth-token-cookie-renewal-max-age cfg)))))))

(t/deftest renew-session-respects-configured-renewal-max-age
  (binding [cf/config (assoc cf/config
                             :auth-token-cookie-renewal-max-age (ct/duration {:minutes 5}))]
    (let [now (ct/now)
          recent (ct/minus now (ct/duration {:minutes 1}))
          stale (ct/minus now (ct/duration {:minutes 10}))]
      (t/is (not (#'session/renew-session? {:id (random-uuid) :modified-at recent})))
      (t/is (#'session/renew-session? {:id (random-uuid) :modified-at stale})))))

(t/deftest renew-session-true-for-legacy-string-session-id
  (t/is (#'session/renew-session? {:id "legacy-string-session" :modified-at (ct/now)})))
