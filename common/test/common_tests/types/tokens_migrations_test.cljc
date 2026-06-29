;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.tokens-migrations-test
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cfo]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/deftest test-fix-missing-sets-in-themes

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

(t/deftest make-tokens-status-from-tokens-lib
  (let [tokens-lib (-> (ctob/make-tokens-lib)
                       (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a)
                                                          :name "set-a"))
                       (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b)
                                                          :name "set-b"))
                       (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-c)
                                                          :name "set-c"))
                       (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-d)
                                                          :name "set-d"))
                       (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                              :name "theme-1"
                                                              :sets #{"set-a" "set-b"}))
                       (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                              :name "theme-2"
                                                              :sets #{"set-b"}))
                       (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-3)
                                                              :name "theme-3"
                                                              :sets #{"set-c" "set-d"})))
        ;; Build a legacy tokens lib to test migration
        tokens-lib (ctob/map->tokens-lib {:sets (.-sets tokens-lib)
                                          :themes (.-themes tokens-lib)
                                          :active-themes #{"/theme-1" "/theme-2"}})

        tokens-status (cfo/make-tokens-status-from-lib tokens-lib)]

    (t/is (ctos/tokens-status? tokens-status))
    (t/is (ctos/check-tokens-status tokens-status))
    (t/is (= (count (ctos/get-active-theme-ids tokens-status)) 2))
    (t/is (ctos/theme-active? tokens-status (thi/id :theme-1)))
    (t/is (ctos/theme-active? tokens-status (thi/id :theme-2)))
    (t/is (= 2 (count (ctos/get-active-set-ids tokens-status))))
    (t/is (ctos/set-active? tokens-status (thi/id :set-a)))
    (t/is (ctos/set-active? tokens-status (thi/id :set-b)))))
