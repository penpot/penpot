;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.token-test
  (:require
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [clojure.test :as t]))

(t/deftest test-valid-token-name-schema
  ;; Allow regular namespace token names
  (t/is (true? (sm/validate cto/schema:token-name "Foo")))
  (t/is (true? (sm/validate cto/schema:token-name "foo")))
  (t/is (true? (sm/validate cto/schema:token-name "FOO")))
  (t/is (true? (sm/validate cto/schema:token-name "Foo.Bar.Baz")))
  ;; Disallow trailing tokens
  (t/is (false? (sm/validate cto/schema:token-name "Foo.Bar.Baz....")))
  ;; Disallow multiple separator dots
  (t/is (false? (sm/validate cto/schema:token-name "Foo..Bar.Baz")))
  ;; Disallow any special characters
  (t/is (false? (sm/validate cto/schema:token-name "Hey Foo.Bar")))
  (t/is (false? (sm/validate cto/schema:token-name "Hey😈Foo.Bar")))
  (t/is (false? (sm/validate cto/schema:token-name "Hey%Foo.Bar"))))


(t/deftest token-value-with-refs
  (t/testing "empty value"
    (t/is (= (cto/insert-ref "" 0 "token1")
             {:value "{token1}" :cursor 8})))

  (t/testing "value without references"
    (t/is (= (cto/insert-ref "ABC" 0 "token1")
             {:value "{token1}ABC" :cursor 8}))
    (t/is (= (cto/insert-ref "23 + " 5 "token1")
             {:value "23 + {token1}" :cursor 13}))
    (t/is (= (cto/insert-ref "23 + " 5 "token1")
             {:value "23 + {token1}" :cursor 13})))

  (t/testing "value with closed references"
    (t/is (= (cto/insert-ref "{token2}" 8 "token1")
             {:value "{token2}{token1}" :cursor 16}))
    (t/is (= (cto/insert-ref "{token2}" 6 "token1")
             {:value "{token1}" :cursor 8}))
    (t/is (= (cto/insert-ref "{token2} + + {token3}" 10 "token1")
             {:value "{token2} +{token1} + {token3}" :cursor 18}))
    (t/is (= (cto/insert-ref "{token2} + {token3}" 16 "token1")
             {:value "{token2} + {token1}" :cursor 19})))

  (t/testing "value with open references"
    (t/is (= (cto/insert-ref "{tok" 4 "token1")
             {:value "{token1}" :cursor 8}))
    (t/is (= (cto/insert-ref "{tok" 2 "token1")
             {:value "{token1}ok" :cursor 8}))
    (t/is (= (cto/insert-ref "{token2}{" 9 "token1")
             {:value "{token2}{token1}" :cursor 16}))
    (t/is (= (cto/insert-ref "{token2{}" 8 "token1")
             {:value "{token2{token1}" :cursor 15}))
    (t/is (= (cto/insert-ref "{token2} + { + token3}" 12 "token1")
             {:value "{token2} + {token1} + token3}" :cursor 19}))
    (t/is (= (cto/insert-ref "{token2{}" 8 "token1")
             {:value "{token2{token1}" :cursor 15}))
    (t/is (= (cto/insert-ref "{token2} + {{{{{{{{{{ + {token3}" 21 "token1")
             {:value "{token2} + {token1} + {token3}" :cursor 19})))

  (t/testing "value with broken references"
    (t/is (= (cto/insert-ref "{tok {en2}" 6 "token1")
             {:value "{tok {token1}" :cursor 13}))
    (t/is (= (cto/insert-ref "{tok en2}" 5 "token1")
             {:value "{tok {token1}en2}" :cursor 13})))

  (t/testing "edge cases"
    (t/is (= (cto/insert-ref "" 0 "x")
             {:value "{x}" :cursor 3}))
    (t/is (= (cto/insert-ref "abc" 3 "x")
             {:value "abc{x}" :cursor 6}))
    (t/is (= (cto/insert-ref "{token2}" 0 "x")
             {:value "{x}{token2}" :cursor 3}))
    (t/is (= (cto/insert-ref "abc" 3 "")
             {:value "abc{}" :cursor 5}))
    (t/is (= (cto/insert-ref "{a} {b}" 4 "x")
             {:value "{a} {x}{b}" :cursor 7}))))

(t/deftest inside-ref
  (t/is (= (cto/inside-ref? ""  1) false))
  (t/is (= (cto/inside-ref? "AAA " 4) false))
  (t/is (= (cto/inside-ref? "{abc" 0) false))
  (t/is (= (cto/inside-ref? "{abc}" 5) false))
  (t/is (= (cto/inside-ref? "{a}{b}" 6) false))
  (t/is (= (cto/inside-ref? "{a{b" 4) true))
  (t/is (= (cto/inside-ref? "abc{" 4) true))
  (t/is (= (cto/inside-ref? "abc}" 4) false))
  (t/is (= (cto/inside-ref? "{abc[}" 1) true))
  (t/is (= (cto/inside-ref? "abc {def}ghi" 5) true))
  (t/is (= (cto/inside-ref? "abc {def]ghi" 8) true)))

(t/deftest inside-closed-ref
  (t/is (= (cto/inside-closed-ref? ""  1) false))
  (t/is (= (cto/inside-closed-ref? "{abc}" 1) true))
  (t/is (= (cto/inside-closed-ref? "abc {def}ghi" 5) true))
  (t/is (= (cto/inside-closed-ref? "abc {def}ghi" 8) true))
  (t/is (= (cto/inside-closed-ref? "abc {def}ghi" 10) false))
  (t/is (= (cto/inside-closed-ref? "{abc}" 0) false))
  (t/is (= (cto/inside-closed-ref? "{abc}" 5) false))
  (t/is (= (cto/inside-closed-ref? "{ab cd}" 3) false))
  (t/is (= (cto/inside-closed-ref? "{a}{bc}" 5) true)))