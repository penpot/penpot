;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.config-test
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [clojure.core :as c]
   [clojure.test :as t]))

(t/deftest tenant-is-normalized-to-lowercase
  (t/testing "an uppercase tenant names the same tenant"
    (t/is (= "acme" (:tenant (#'cf/normalize-tenant {:tenant "ACME"}))))
    (t/is (= "acme" (:tenant (#'cf/normalize-tenant {:tenant "acme"})))))
  (t/testing "a config without a tenant is left untouched"
    (t/is (= {:x 1} (#'cf/normalize-tenant {:x 1}))))
  (t/testing "the validated form is lowercase-only"
    (t/is (not (sm/validate cf/schema:tenant "ACME")))
    (t/is (sm/validate cf/schema:tenant "acme"))))

(t/deftest get-public-uri-normalizes-base
  (t/testing "trailing slash is ensured with and without subpath"
    (doseq [[base expected] [["http://localhost:3449" "http://localhost:3449/"]
                             ["http://localhost:3449/" "http://localhost:3449/"]
                             ["https://example.com/penpot" "https://example.com/penpot/"]
                             ["https://example.com/penpot/" "https://example.com/penpot/"]]]
      (t/testing (str "base " base)
        (with-redefs [cf/config (assoc cf/config :public-uri base)]
          (t/is (= expected (cf/get-public-uri))))))))

(t/deftest get-public-uri-preserves-subpath
  (t/testing "joined segments keep the subpath"
    (with-redefs [cf/config (assoc cf/config :public-uri "https://example.com/penpot")]
      (t/is (= "https://example.com/penpot/assets/by-id/123"
               (cf/get-public-uri "assets/by-id/123")))
      (t/is (= "https://example.com/penpot/api/main/doc"
               (cf/get-public-uri "api/main/doc"))))))

(t/deftest join-uri-joins-arbitrary-base
  (t/testing "segments join onto any base with trailing slash normalization"
    (t/is (= "https://nitrate.example.com/api/teams/123"
             (cf/join-uri "https://nitrate.example.com" "api/teams/123")))
    (t/is (= "https://nitrate.example.com/api/teams/123"
             (cf/join-uri "https://nitrate.example.com/" "api/teams/123")))))

(t/deftest join-uri-rejects-leading-slash
  (t/testing "a leading slash would drop the subpath, so it fails fast"
    (t/is (thrown? AssertionError
                   (cf/join-uri "https://example.com/penpot" "/assets/by-id/123")))))

(t/deftest tenant-allows-only-alphanumeric
  (t/testing "default and plain alphanumeric tenants validate"
    (doseq [tenant ["default" "acme42" "my-company"]]
      (t/is (cf/validate-config (assoc cf/config :tenant tenant)))))
  (t/testing "LIKE wildcards and separators are rejected"
    (doseq [tenant ["my_tenant" "a%b" "a:b" "a b" "" "acme.example.com"]]
      (t/is (not (cf/validate-config (assoc cf/config :tenant tenant)))))))

(t/deftest worker-parallelism-keys
  (t/testing "schema decodes all runner parallelism keys (env strings to int)"
    (let [decoded (cf/decode-config {:worker-default-parallelism "4"
                                     :worker-webhook-parallelism "2"
                                     :worker-cron-parallelism "3"
                                     :worker-binfile-parallelism "5"})]
      (t/is (= 4 (:worker-default-parallelism decoded)))
      (t/is (= 2 (:worker-webhook-parallelism decoded)))
      (t/is (= 3 (:worker-cron-parallelism decoded)))
      (t/is (= 5 (:worker-binfile-parallelism decoded)))))
  (t/testing "lookups resolve configured values (unnamespaced keys, as main uses)"
    (with-redefs [cf/config (assoc cf/config
                                   :worker-default-parallelism 4)]
      (t/is (= 4 (cf/get :worker-default-parallelism 1))))))

(t/deftest prepared-statement-cache-keys
  (t/testing "negative sizes are rejected; the threshold takes -1 or non-negatives"
    (t/is (not (cf/validate-config (assoc cf/config :database-prepared-statement-cache-queries -1))))
    (t/is (not (cf/validate-config (assoc cf/config :database-prepared-statement-cache-size -1))))
    (t/is (not (cf/validate-config (assoc cf/config :database-prepared-statement-cache-threshold -2))))
    (t/is (cf/validate-config (assoc cf/config :database-prepared-statement-cache-threshold -1))))
  (t/testing "a -1 threshold survives the environment decoding and validation"
    (let [cfg (merge cf/config
                     (cf/decode-config {:database-prepared-statement-cache-threshold "-1"}))]
      (t/is (= -1 (:database-prepared-statement-cache-threshold cfg)))
      (t/is (cf/validate-config cfg))))
  (t/testing "a decoded -2 threshold fails validation"
    (let [cfg (merge cf/config
                     (cf/decode-config {:database-prepared-statement-cache-threshold "-2"}))]
      (t/is (= -2 (:database-prepared-statement-cache-threshold cfg)))
      (t/is (not (cf/validate-config cfg))))))

(t/deftest jobs-user-ttl
  (t/testing "the env value is decoded as a duration"
    (let [decoded (cf/decode-config {:jobs-user-ttl "72h"})]
      (t/is (= (ct/duration {:days 3}) (:jobs-user-ttl decoded)))))

  (t/testing "the getter resolves the configured value"
    (with-redefs [cf/config (assoc cf/config :jobs-user-ttl (ct/duration {:days 1}))]
      (t/is (= (ct/duration {:days 1}) (cf/get-jobs-user-ttl)))))

  (t/testing "and falls back to a week"
    (with-redefs [cf/config (dissoc cf/config :jobs-user-ttl)]
      (t/is (= (ct/duration {:days 7}) (cf/get-jobs-user-ttl))))))

;; `legacy-db` is read-only: the file data moved to `file_data`, and
;; `file.data` / `file_change.data` are read-only leftovers. An instance
;; that keeps it starts healthy and then fails on every save. It is read
;; as `db` instead, which also serves the legacy rows.

(defn- read-config
  "`read-config` with the environment replaced by `env`."
  [env & {:keys [default] :or {default {}}}]
  (with-redefs [cf/read-env (constantly env)]
    (cf/read-config :default default)))

(defn- read-config-with-warning
  "Reads the configuration and collects the warnings it emits. Logging is
  emitted asynchronously, so the record is awaited (bounded)."
  [env]
  (let [records (atom [])]
    (add-watch l/log-record ::capture
               (fn [_ _ _ record] (swap! records conj record)))
    (try
      (let [config (read-config env)]
        [(loop [remaining 100]
           (let [warns (filter #(= :warn (::l/level %)) @records)]
             (cond
               (seq warns) warns
               (zero? remaining) warns
               :else (do (Thread/sleep 10) (recur (dec remaining))))))
         config])
      (finally
        (remove-watch l/log-record ::capture)))))

(t/deftest read-config-replaces-the-read-only-legacy-db
  (let [[warns config] (read-config-with-warning {:file-data-backend "legacy-db"})]
    (t/testing "the configuration writes through db"
      (t/is (= "db" (c/get config :file-data-backend))))
    (t/testing "the operator is warned"
      (t/is (contains? (into #{} (map #(c/get (::l/props %) :hint)) warns)
                       "file-data-backend=legacy-db is read-only, using db")))))

(t/deftest read-config-keeps-the-writable-back-ends
  (doseq [backend ["db" "storage"]]
    (t/testing backend
      (t/is (= backend (c/get (read-config {:file-data-backend backend})
                              :file-data-backend))))))

(t/deftest read-config-takes-the-default-back-end
  (t/is (= "db" (c/get (read-config {} :default cf/default)
                       :file-data-backend))))
