;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.config-test
  (:require
   [app.common.logging :as l]
   [app.config :as cf]
   [clojure.core :as c]
   [clojure.test :as t]))

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

;; `legacy-db` is read-only since the file data moved to `file_change`: an
;; instance that keeps it starts healthy and then fails on every save. It is
;; read as `db` instead, which also serves the legacy rows.

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
