;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.tokens-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.time :as ct]
   [app.common.types.tokens-lib :as ctob]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/deftest test-v1-5-fix-token-names

  (t/testing "empty tokens-lib should not need any action"
    (let [tokens-lib  (ctob/make-tokens-lib)
          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)]
      (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

  (t/testing "tokens with valid names should not need any action"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"
                                         :tokens {"name1" (ctob/make-token
                                                           {:id (thi/new-id! :token1)
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "1"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"
                                         :tokens {"name1" (ctob/make-token            ;; Same name in different
                                                           {:id (thi/new-id! :token2) ;; sets is ok
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "2"})})))

          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)]

      (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

  (t/testing "tokens with conflicting names should be renamed, and the rest of the library should be unchanged"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"
                                         :tokens {"name1" (ctob/make-token
                                                           {:id (thi/new-id! :token1)
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "1"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"
                                         :tokens {"name1.name2" (ctob/make-token
                                                                 {:id (thi/new-id! :token2)
                                                                  :name "name1.name2"
                                                                  :type :border-radius
                                                                  :value "2"})})))

          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)

          token-sets  (ctob/get-set-tree tokens-lib)
          set1        (ctob/get-set tokens-lib (thi/id :set1))
          set2        (ctob/get-set tokens-lib (thi/id :set2))
          tokens1     (ctob/get-tokens tokens-lib (thi/id :set1))
          tokens2     (ctob/get-tokens tokens-lib (thi/id :set2))
          token1      (ctob/get-token tokens-lib (thi/id :set1) (thi/id :token1))
          token2      (ctob/get-token tokens-lib (thi/id :set2) (thi/id :token2))

          token-sets' (ctob/get-set-tree tokens-lib')
          set1'       (ctob/get-set tokens-lib' (thi/id :set1))
          set2'       (ctob/get-set tokens-lib' (thi/id :set2))
          tokens1'    (ctob/get-tokens tokens-lib' (thi/id :set1))
          tokens2'    (ctob/get-tokens tokens-lib' (thi/id :set2))
          token1'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token1))
          token2'     (ctob/get-token tokens-lib' (thi/id :set2) (thi/id :token2))]

      (t/is (= (count token-sets') (count token-sets)))

      (t/is (= (ctob/get-id set1') (ctob/get-id set1)))
      (t/is (= (ctob/get-name set1') (ctob/get-name set1)))
      (t/is (= (ctob/get-description set1') (ctob/get-description set1)))
      (t/is (ct/is-after-or-equal? (ctob/get-modified-at set1') (ctob/get-modified-at set1))) ;; <-- MODIFIED

      (t/is (= (ctob/get-id set2') (ctob/get-id set2)))
      (t/is (= (ctob/get-name set2') (ctob/get-name set2)))
      (t/is (= (ctob/get-description set2') (ctob/get-description set2)))
      (t/is (= (ctob/get-modified-at set2') (ctob/get-modified-at set2)))

      (t/is (= (count tokens1') (count tokens1)))
      (t/is (= (count tokens2') (count tokens2)))

      (t/is (= (ctob/get-id token1') (ctob/get-id token1)))
      (t/is (= (ctob/get-name token1') "name1-1"))                                            ;; <-- RENAMED
      (t/is (= (ctob/get-description token1') (ctob/get-description token1)))
      (t/is (ct/is-after-or-equal? (ctob/get-modified-at set1') (ctob/get-modified-at set1))) ;; <-- MODIFIED
      (t/is (= (:type token1') (:type token1)))
      (t/is (= (:value token1') (:value token1)))

      (t/is (= (ctob/get-id token2') (ctob/get-id token2)))
      (t/is (= (ctob/get-name token2') (ctob/get-name token2)))
      (t/is (= (ctob/get-description token2') (ctob/get-description token2)))
      (t/is (= (ctob/get-modified-at token2') (ctob/get-modified-at token2)))
      (t/is (= (:type token2') (:type token2)))
      (t/is (= (:value token2') (:value token2)))))

  (t/testing "the renamed token is always the first one found with a conflicting name"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"
                                         :tokens {"name1.name2" (ctob/make-token
                                                                 {:id (thi/new-id! :token1)
                                                                  :name "name1.name2"
                                                                  :type :border-radius
                                                                  :value "1"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"
                                         :tokens {"name1" (ctob/make-token
                                                           {:id (thi/new-id! :token2)
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "2"})})))

          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)
          token1'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token1))
          token2'     (ctob/get-token tokens-lib' (thi/id :set2) (thi/id :token2))]

      (t/is (= "name1-1.name2" (ctob/get-name token1')))
      (t/is (= "name1" (ctob/get-name token2')))))

  (t/testing "several tokens with the same conflicting prefix should be assigned the same number as suffixes"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"
                                         :tokens {"name1.name2" (ctob/make-token
                                                                 {:id (thi/new-id! :token1)
                                                                  :name "name1.name2"
                                                                  :type :border-radius
                                                                  :value "1"})
                                                  "name1.name3" (ctob/make-token
                                                                 {:id (thi/new-id! :token2)
                                                                  :name "name1.name3"
                                                                  :type :border-radius
                                                                  :value "2"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"
                                         :tokens {"name1" (ctob/make-token
                                                           {:id (thi/new-id! :token3)
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "3"})})))

          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)
          token1'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token1))
          token2'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token2))
          token3'     (ctob/get-token tokens-lib' (thi/id :set2) (thi/id :token3))]

      (t/is (= "name1-1.name2" (ctob/get-name token1')))
      (t/is (= "name1-1.name3" (ctob/get-name token2')))
      (t/is (= "name1" (ctob/get-name token3')))))

  (t/testing "tokens with diferent conflicting prefixes should be assigned consecutive numbers as suffixes"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"
                                         :tokens {"name1" (ctob/make-token
                                                           {:id (thi/new-id! :token1)
                                                            :name "name1"
                                                            :type :border-radius
                                                            :value "1"})
                                                  "name2" (ctob/make-token
                                                           {:id (thi/new-id! :token2)
                                                            :name "name2"
                                                            :type :border-radius
                                                            :value "2"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"
                                         :tokens {"name1.subname1" (ctob/make-token
                                                                    {:id (thi/new-id! :token3)
                                                                     :name "name1.subname1"
                                                                     :type :border-radius
                                                                     :value "3"})}))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set3)
                                         :name "set3"
                                         :tokens {"name2.subname2" (ctob/make-token
                                                                    {:id (thi/new-id! :token4)
                                                                     :name "name2.subname2"
                                                                     :type :border-radius
                                                                     :value "3"})})))

          tokens-lib' (ctob/fix-conflicting-token-names tokens-lib)
          token1'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token1))
          token2'     (ctob/get-token tokens-lib' (thi/id :set1) (thi/id :token2))
          token3'     (ctob/get-token tokens-lib' (thi/id :set2) (thi/id :token3))
          token4'     (ctob/get-token tokens-lib' (thi/id :set3) (thi/id :token4))]

      (t/is (= "name1-1" (ctob/get-name token1')))
      (t/is (= "name2-2" (ctob/get-name token2')))
      (t/is (= "name1.subname1" (ctob/get-name token3')))
      (t/is (= "name2.subname2" (ctob/get-name token4'))))))

(t/deftest test-v1-6-fix-token-names

  (t/testing "empty tokens-lib should not need any action"
    (let [tokens-lib  (ctob/make-tokens-lib)
          tokens-lib' (ctob/fix-missing-sets-in-themes tokens-lib)]
      (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

  (t/testing "library with a valid theme should not need any action"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"))
                          (ctob/add-theme (ctob/make-token-theme
                                           :id (thi/new-id! :theme1)
                                           :name "theme1"
                                           :sets #{"set1"})))
          tokens-lib' (ctob/fix-missing-sets-in-themes tokens-lib)]
      (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

  (t/testing "library with a theme containing a non-existent set should have it removed, and the rest of the library should be unchanged"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set1)
                                         :name "set1"))
                          (ctob/add-set (ctob/make-token-set
                                         :id (thi/new-id! :set2)
                                         :name "set2"))
                          (ctob/add-theme (ctob/make-token-theme
                                           :id (thi/new-id! :theme1)
                                           :name "theme1"
                                           :sets #{"set1" "set3"}))  ;; "set3" does not exist
                          (ctob/add-theme (ctob/make-token-theme
                                           :id (thi/new-id! :theme2)
                                           :name "theme2"
                                           :sets #{"set1" "set2"})))
          tokens-lib' (ctob/fix-missing-sets-in-themes tokens-lib)

          set1        (ctob/get-set tokens-lib (thi/id :set1))
          set2        (ctob/get-set tokens-lib (thi/id :set2))
          theme1      (ctob/get-theme tokens-lib (thi/id :theme1))
          theme2      (ctob/get-theme tokens-lib (thi/id :theme2))
          set1'       (ctob/get-set tokens-lib' (thi/id :set1))
          set2'       (ctob/get-set tokens-lib' (thi/id :set2))
          theme1'     (ctob/get-theme tokens-lib' (thi/id :theme1))
          theme2'     (ctob/get-theme tokens-lib' (thi/id :theme2))]

      (t/is (= (:sets theme1') #{"set1"}))
      (t/is (= (:sets theme2') #{"set1" "set2"}))

      (t/is (= (ctob/get-id set1') (ctob/get-id set1)))
      (t/is (= (ctob/get-name set1') (ctob/get-name set1)))
      (t/is (= (ctob/get-description set1') (ctob/get-description set1)))
      (t/is (= (ctob/get-modified-at set1') (ctob/get-modified-at set1)))
      (t/is (= (ctob/get-id set2') (ctob/get-id set2)))
      (t/is (= (ctob/get-name set2') (ctob/get-name set2)))
      (t/is (= (ctob/get-description set2') (ctob/get-description set2)))
      (t/is (= (ctob/get-modified-at set2') (ctob/get-modified-at set2)))

      (t/is (= (ctob/get-id theme1') (ctob/get-id theme1)))
      (t/is (= (ctob/get-name theme1') (ctob/get-name theme1)))
      (t/is (= (ctob/get-description theme1') (ctob/get-description theme1)))
      (t/is (= (ctob/get-id theme2') (ctob/get-id theme2)))
      (t/is (= (ctob/get-name theme2') (ctob/get-name theme2)))
      (t/is (= (ctob/get-description theme2') (ctob/get-description theme2))))))
