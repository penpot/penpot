;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.undo-stack-test
  (:require
   [app.common.data.undo-stack :as sut]
   [clojure.test :as t]))

;; --- make-stack ---

(t/deftest make-stack-creates-empty-stack
  (let [stack (sut/make-stack)]
    (t/is (= -1 (:index stack)))
    (t/is (= [] (:items stack)))))

(t/deftest make-stack-returns-nil-on-peek
  (t/is (nil? (sut/peek (sut/make-stack)))))

(t/deftest make-stack-size-is-zero
  (t/is (= 0 (sut/size (sut/make-stack)))))

;; --- peek ---

(t/deftest peek-empty-stack
  (t/is (nil? (sut/peek (sut/make-stack)))))

(t/deftest peek-after-append
  (let [stack (-> (sut/make-stack)
                  (sut/append :a))]
    (t/is (= :a (sut/peek stack)))))

(t/deftest peek-multiple-items
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c))]
    (t/is (= :c (sut/peek stack)))))

(t/deftest peek-after-undo
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo))]
    (t/is (= :a (sut/peek stack)))))

;; --- append ---

(t/deftest append-to-nil-stack
  (t/is (nil? (sut/append nil :a))))

(t/deftest append-single-item
  (let [stack (-> (sut/make-stack)
                  (sut/append :a))]
    (t/is (= 0 (:index stack)))
    (t/is (= [:a] (:items stack)))))

(t/deftest append-multiple-items
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c))]
    (t/is (= 2 (:index stack)))
    (t/is (= [:a :b :c] (:items stack)))))

(t/deftest append-duplicate-at-current-index-ignored
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :a))]
    (t/is (= 0 (:index stack)))
    (t/is (= [:a] (:items stack)))))

(t/deftest append-duplicate-after-other-operations
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo)
                  (sut/append :a))]
    ;; appending :a when current is :a should be a no-op
    (t/is (= 0 (:index stack)))
    (t/is (= [:a :b] (:items stack)))))

(t/deftest append-same-value-at-different-positions-allowed
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :a))]
    (t/is (= 2 (:index stack)))
    (t/is (= [:a :b :a] (:items stack)))))

(t/deftest append-nil-value-returns-unchanged
  ;; appending nil when peek is nil returns stack unchanged
  (let [stack (sut/make-stack)
        result (sut/append stack nil)]
    (t/is (= stack result))))

(t/deftest append-complex-values
  (let [v1 {:id 1 :name "shape"}
        v2 {:id 2 :name "rect"}
        stack (-> (sut/make-stack)
                  (sut/append v1)
                  (sut/append v2))]
    (t/is (= 1 (:index stack)))
    (t/is (= v2 (sut/peek stack)))))

;; --- append truncates redo history ---

(t/deftest append-truncates-redo-history-at-index-greater-than-zero
  ;; Truncation only happens when index > 0
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/append :d)
                  (sut/undo)                ;; index -> 2, peek :c
                  (sut/append :e))]         ;; index > 0, truncates :d
    (t/is (= 3 (:index stack)))
    (t/is (= [:a :b :c :e] (:items stack)))
    (t/is (= :e (sut/peek stack)))))

(t/deftest append-at-index-zero-does-not-truncate
  ;; When index is 0, append just adds to end without truncation
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)                ;; index -> 1
                  (sut/undo)                ;; index -> 0
                  (sut/append :d))]
    ;; index 0, no truncation: items become [:a :b :c :d]
    (t/is (= 1 (:index stack)))
    (t/is (= [:a :b :c :d] (:items stack)))
    (t/is (= :b (sut/peek stack)))))

(t/deftest append-truncates-multiple-redo-items
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/append :d)
                  (sut/append :e)
                  (sut/undo)                ;; index -> 3, peek :d
                  (sut/undo)                ;; index -> 2, peek :c
                  (sut/append :x))]
    (t/is (= 3 (:index stack)))
    (t/is (= [:a :b :c :x] (:items stack)))))

;; --- append respects MAX-UNDO-SIZE ---

(t/deftest append-max-undo-size-boundary
  (let [;; Fill stack to MAX-UNDO-SIZE items
        stack (reduce (fn [s i] (sut/append s (str "item-" i)))
                      (sut/make-stack)
                      (range sut/MAX-UNDO-SIZE))]
    (t/is (= (dec sut/MAX-UNDO-SIZE) (:index stack)))
    (t/is (= sut/MAX-UNDO-SIZE (count (:items stack))))
    (t/is (= "item-0" (first (:items stack))))
    (t/is (= (str "item-" (dec sut/MAX-UNDO-SIZE)) (sut/peek stack)))))

(t/deftest append-exceeds-max-undo-size-removes-oldest
  (let [;; Fill stack to MAX-UNDO-SIZE + 1
        stack (reduce (fn [s i] (sut/append s (str "item-" i)))
                      (sut/make-stack)
                      (range (inc sut/MAX-UNDO-SIZE)))]
    (t/is (= (dec sut/MAX-UNDO-SIZE) (:index stack)))
    (t/is (= sut/MAX-UNDO-SIZE (count (:items stack))))
    ;; Oldest item was removed
    (t/is (= "item-1" (first (:items stack))))
    (t/is (= (str "item-" sut/MAX-UNDO-SIZE) (sut/peek stack)))))

(t/deftest append-far-exceeds-max-undo-size
  (let [;; Fill stack to MAX-UNDO-SIZE + 10
        stack (reduce (fn [s i] (sut/append s (str "item-" i)))
                      (sut/make-stack)
                      (range (+ sut/MAX-UNDO-SIZE 10)))]
    (t/is (= (dec sut/MAX-UNDO-SIZE) (:index stack)))
    (t/is (= sut/MAX-UNDO-SIZE (count (:items stack))))
    (t/is (= "item-10" (first (:items stack))))))

;; --- fixup ---

(t/deftest fixup-updates-current-item
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/fixup :a-updated))]
    (t/is (= :a-updated (sut/peek stack)))
    (t/is (= 0 (:index stack)))))

(t/deftest fixup-at-middle-of-stack
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)                ;; index -> 1
                  (sut/fixup :b-updated))]
    (t/is (= :b-updated (sut/peek stack)))
    (t/is (= [:a :b-updated :c] (:items stack)))))

(t/deftest fixup-preserves-index
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo)                ;; index -> 0
                  (sut/fixup :a-new))]
    (t/is (= 0 (:index stack)))
    (t/is (= :a-new (sut/peek stack)))))

;; --- undo ---

(t/deftest undo-single-item
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/undo))]
    (t/is (= 0 (:index stack)))
    (t/is (= :a (sut/peek stack)))))

(t/deftest undo-clamps-to-zero
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/undo)
                  (sut/undo))]
    (t/is (= 0 (:index stack)))
    (t/is (= :a (sut/peek stack)))))

(t/deftest undo-multiple-steps
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)
                  (sut/undo))]
    (t/is (= 0 (:index stack)))
    (t/is (= :a (sut/peek stack)))))

(t/deftest undo-on-empty-stack
  ;; undo on empty stack clamps index to 0 (from -1, dec gives -2, max with 0 gives 0)
  (let [stack (-> (sut/make-stack)
                  (sut/undo))]
    (t/is (= 0 (:index stack)))
    (t/is (nil? (sut/peek stack)))))

(t/deftest undo-preserves-items
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo))]
    (t/is (= [:a :b] (:items stack)))))

;; --- redo ---

(t/deftest redo-after-undo
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo)
                  (sut/redo))]
    (t/is (= 1 (:index stack)))
    (t/is (= :b (sut/peek stack)))))

(t/deftest redo-at-end-of-stack-no-op
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/redo))]
    (t/is (= 1 (:index stack)))
    (t/is (= :b (sut/peek stack)))))

(t/deftest redo-multiple-steps
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)                ;; index -> 1
                  (sut/undo)                ;; index -> 0
                  (sut/redo)                ;; index -> 1
                  (sut/redo))]              ;; index -> 2
    (t/is (= 2 (:index stack)))
    (t/is (= :c (sut/peek stack)))))

(t/deftest redo-on-empty-stack
  (let [stack (-> (sut/make-stack)
                  (sut/redo))]
    (t/is (= -1 (:index stack)))))

(t/deftest redo-not-available-after-truncating-append
  ;; When index > 0, append truncates redo history
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)                ;; index -> 1
                  (sut/append :x)           ;; truncates :c, items [:a :b :x]
                  (sut/redo))]
    ;; Redo should not work since redo history was truncated
    (t/is (= 2 (:index stack)))
    (t/is (= :x (sut/peek stack)))))

(t/deftest redo-after-append-at-index-zero
  ;; When index is 0, append does not truncate, so old items remain
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)                ;; index -> 1
                  (sut/undo)                ;; index -> 0
                  (sut/append :x)           ;; index 0, no truncation: [:a :b :c :x]
                  (sut/redo))]
    ;; redo goes from index 1 to 2, which is :c
    (t/is (= 2 (:index stack)))
    (t/is (= :c (sut/peek stack)))))

;; --- size ---

(t/deftest size-empty-stack
  (t/is (= 0 (sut/size (sut/make-stack)))))

(t/deftest size-after-appends
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c))]
    (t/is (= 3 (sut/size stack)))))

(t/deftest size-unchanged-after-undo
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo))]
    ;; size returns (inc index), not count of items
    (t/is (= 2 (sut/size stack)))))

(t/deftest size-is-undo-position
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/undo)
                  (sut/undo))]
    (t/is (= 1 (sut/size stack)))))

;; --- undo/redo round-trip ---

(t/deftest undo-redo-round-trip
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c))]
    (t/is (= :c (sut/peek stack)))
    (let [s (sut/undo stack)]
      (t/is (= :b (sut/peek s)))
      (let [s (sut/undo s)]
        (t/is (= :a (sut/peek s)))
        (let [s (sut/undo s)]
          ;; At index 0, undo should clamp
          (t/is (= :a (sut/peek s)))
          (t/is (= 0 (:index s)))
          (let [s (sut/redo s)]
            (t/is (= :b (sut/peek s)))
            (let [s (sut/redo s)]
              (t/is (= :c (sut/peek s)))
              (let [s (sut/redo s)]
                ;; At end, redo should be no-op
                (t/is (= :c (sut/peek s)))))))))))

;; --- mixed operations ---

(t/deftest append-after-undo-then-redo
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/undo)
                  (sut/undo)
                  (sut/redo)
                  (sut/append :c))]
    (t/is (= 2 (:index stack)))
    (t/is (= [:a :b :c] (:items stack)))
    (t/is (= :c (sut/peek stack)))))

(t/deftest fixup-then-append
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/fixup :a-fixed)
                  (sut/append :b))]
    (t/is (= [:a-fixed :b] (:items stack)))
    (t/is (= :b (sut/peek stack)))))

(t/deftest append-identical-different-objects
  (let [m1 {:x 1}
        m2 {:x 1}
        stack (-> (sut/make-stack)
                  (sut/append m1)
                  (sut/append m2))]
    ;; Maps are equal, so second append should be a no-op
    (t/is (= 0 (:index stack)))
    (t/is (= [m1] (:items stack)))))

(t/deftest append-maps-not-equal
  (let [m1 {:x 1}
        m2 {:x 2}
        stack (-> (sut/make-stack)
                  (sut/append m1)
                  (sut/append m2))]
    (t/is (= 1 (:index stack)))
    (t/is (= [m1 m2] (:items stack)))))

(t/deftest sequential-fixup-and-undo
  (let [stack (-> (sut/make-stack)
                  (sut/append {:id 1 :val "old"})
                  (sut/append {:id 2 :val "old"})
                  (sut/fixup {:id 2 :val "new"})
                  (sut/undo)
                  (sut/fixup {:id 1 :val "updated"}))]
    (t/is (= {:id 1 :val "updated"} (sut/peek stack)))
    (t/is (= [{:id 1 :val "updated"} {:id 2 :val "new"}] (:items stack)))))

(t/deftest append-undo-append-undo-cycle
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)           ;; index 2
                  (sut/undo)                ;; index 1, peek :b
                  (sut/append :d)           ;; index 1 > 0, truncates :c, items [:a :b :d]
                  (sut/append :e)           ;; items [:a :b :d :e]
                  (sut/undo)                ;; index 2, peek :d
                  (sut/undo))]              ;; index 1, peek :b
    (t/is (= 1 (:index stack)))
    (t/is (= :b (sut/peek stack)))
    (t/is (= [:a :b :d :e] (:items stack)))))

(t/deftest size-grows-then-shrinks-with-undo
  (let [stack (-> (sut/make-stack)
                  (sut/append :a)
                  (sut/append :b)
                  (sut/append :c)
                  (sut/append :d))]
    (t/is (= 4 (sut/size stack)))
    (let [s (sut/undo stack)]
      (t/is (= 3 (sut/size s)))
      (let [s (sut/undo s)]
        (t/is (= 2 (sut/size s)))
        (let [s (sut/redo s)]
          (t/is (= 3 (sut/size s))))))))
