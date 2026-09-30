;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.crypto-test
  "Sealing of the session token a queued job carries through redis."
  (:require
   [app.util.crypto :as crypto]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]))

(t/deftest sealed-value-opens-to-the-original
  (let [token "eyJhbGciOiJIUzI1NiJ9.session-token"]
    (t/is (= token (crypto/decrypt (crypto/encrypt token))))))

(t/deftest sealed-value-does-not-contain-the-plaintext
  (let [token "a-very-recognisable-session-token"]
    (t/is (not (str/includes? (crypto/encrypt token) token)))))

(t/deftest sealing-twice-gives-different-values
  (t/testing "a fresh iv per value, so equal tokens are not recognisable in redis"
    (t/is (not= (crypto/encrypt "same") (crypto/encrypt "same")))))

(t/deftest altered-value-is-rejected
  (let [sealed           (crypto/encrypt "token")
        [iv tag body]    (str/split sealed ".")
        flipped          (str (if (= "A" (subs body 0 1)) "B" "A") (subs body 1))
        tampered         (str iv "." tag "." flipped)]
    (t/is (thrown? js/Error (crypto/decrypt tampered)))))

(t/deftest malformed-value-is-rejected
  (t/is (thrown? js/Error (crypto/decrypt "not-a-sealed-value"))))
