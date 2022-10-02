;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.pages-helpers-test
  (:require
   [clojure.test :as t]
   [clojure.pprint :refer [pprint]]
   [app.common.pages.helpers :as cph]))

(t/deftest insert-at-index
  ;; insert different object
  (t/is (= (cph/insert-at-index [:a :b] 1 [:c :d])
           [:a :c :d :b]))

  ;; insert on the start
  (t/is (= (cph/insert-at-index [:a :b] 0 [:c])
           [:c :a :b]))

  ;; insert on the end 1
  (t/is (= (cph/insert-at-index [:a :b] 2 [:c])
           [:a :b :c]))

  ;; insert on the end with not existing index
  (t/is (= (cph/insert-at-index [:a :b] 10 [:c])
           [:a :b :c]))

  ;; insert existing in a contiguous index
  (t/is (= (cph/insert-at-index [:a :b] 1 [:a])
           [:a :b]))

  ;; insert existing in the same index
  (t/is (= (cph/insert-at-index [:a :b] 0 [:a])
           [:a :b]))

  ;; insert existing in other index case 1
  (t/is (= (cph/insert-at-index [:a :b :c] 2 [:a])
           [:b :a :c]))

  ;; insert existing in other index case 2
  (t/is (= (cph/insert-at-index [:a :b :c :d] 0 [:d])
           [:d :a :b :c]))

  ;; insert existing in other index case 3
  (t/is (= (cph/insert-at-index [:a :b :c :d] 1 [:a])
           [:a :b :c :d]))

  )


(t/deftest parse-path-name
  (t/is (= ["foo" "bar"] (cph/parse-path-name "foo/bar")))
  (t/is (= ["" "foo"] (cph/parse-path-name "foo")))
  (t/is (= ["" "foo"] (cph/parse-path-name "/foo")))
  (t/is (= ["" ""] (cph/parse-path-name "")))
  (t/is (= ["" ""] (cph/parse-path-name nil)))
  )
