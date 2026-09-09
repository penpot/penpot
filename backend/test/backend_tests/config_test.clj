;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.config-test
  (:require
   [app.config :as cf]
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
