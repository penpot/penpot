;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.exceptions-test
  "Exception message bounding in `app.common.exceptions`.

  A message can carry a whole payload (a serialized file, for instance) and
  grow to megabytes. `get-hint` and the CLJS `format-throwable` keep a bounded
  head so neither the audit `:hint` prop nor the report can explode."
  (:require
   #?(:cljs [cuerdas.core :as str])
   [app.common.exceptions :as ex]
   [clojure.test :as t]))

(defn- huge-string
  [n]
  (apply str (repeat n "x")))

(t/deftest first-line-keeps-a-single-line-message-intact-test
  (t/is (= "boom" (ex/first-line "boom")))
  (t/is (= "boom" (ex/first-line "boom\nrest of the message"))))

(t/deftest get-hint-returns-the-first-line-test
  (t/is (= "boom" (ex/get-hint (ex-info "boom\nsecond line" {})))))

(t/deftest get-hint-prefers-the-ex-data-hint-test
  (t/is (= "from-data" (ex/get-hint (ex-info "from-message" {:hint "from-data"})))))

(t/deftest get-hint-bounds-a-huge-message-test
  (let [huge (huge-string (* 4 ex/max-message-length))
        hint (ex/get-hint (ex-info huge {}))]
    (t/is (string? hint))
    (t/is (<= (count hint) (+ ex/max-message-length 3)))
    (t/is (= (subs hint 0 32) (subs huge 0 32)))))

(t/deftest get-hint-bounds-a-huge-ex-data-hint-test
  (let [huge (huge-string (* 4 ex/max-message-length))
        hint (ex/get-hint (ex-info "short" {:hint huge}))]
    (t/is (<= (count hint) (+ ex/max-message-length 3)))
    (t/is (= (subs hint 0 32) (subs huge 0 32)))))

#?(:cljs
   (t/deftest format-throwable-bounds-a-huge-trace-message-test
     (let [huge (huge-string (* 4 ex/max-message-length))
           out  (ex/format-throwable (ex-info huge {}))
           msg  (some #(when (str/includes? % (subs huge 0 16)) %) (str/lines out))]
       (t/is (some? msg))
       (t/is (<= (count msg) (+ ex/max-message-length 16))))))

#?(:cljs
   (t/deftest format-throwable-honors-trace-length-test
     (let [full  (ex/format-throwable (ex-info "boom" {}))
           short (ex/format-throwable (ex-info "boom" {}) {:trace-length 1})]
       (t/is (< (count (str/lines short)) (count (str/lines full)))))))
