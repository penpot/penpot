;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.data-test
  (:require
   [app.common.data :as d]
   [clojure.test :as t]))

(t/deftest concat-vec
  (t/is (= []    (d/concat-vec)))
  (t/is (= [1]   (d/concat-vec [1])))
  (t/is (= [1]   (d/concat-vec #{1})))
  (t/is (= [1 2] (d/concat-vec [1] #{2})))
  (t/is (= [1 2] (d/concat-vec '(1) [2]))))

(t/deftest concat-set
  (t/is (= #{} (d/concat-set)))
  (t/is (= #{1 2}
           (d/concat-set [1] [2]))))

(t/deftest remove-at-index
  (t/is (= [1 2 3 4]
           (d/remove-at-index [1 2 3 4 5] 4)))


  (t/is (= [1 2 3 4]
           (d/remove-at-index [5 1 2 3 4] 0)))

  (t/is (= [1 2 3 4]
           (d/remove-at-index [1 5 2 3 4] 1)))
  )

(t/deftest with-next
  (t/is (= [[0 1] [1 2] [2 3] [3 4] [4 nil]]
           (d/with-next (range 5)))))

(t/deftest with-prev
  (t/is (= [[0 nil] [1 0] [2 1] [3 2] [4 3]]
           (d/with-prev (range 5)))))

(t/deftest with-prev-next
  (t/is (= [[0 nil 1] [1 0 2] [2 1 3] [3 2 4] [4 3 nil]]
           (d/with-prev-next (range 5)))))

(t/deftest join
  (t/is (= [[1 :a] [1 :b] [2 :a] [2 :b] [3 :a] [3 :b]]
           (d/join [1 2 3] [:a :b])))
  (t/is (= [1 10 100 2 20 200 3 30 300]
           (d/join [1 2 3] [1 10 100] *))))

