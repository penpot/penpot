;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.files.tokens-test
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [clojure.test :as t]))

(t/deftest test-parse-token-value
  (t/testing "parses double from a token value"
    (t/is (= {:value 100.1 :unit nil} (cfo/parse-token-value "100.1")))
    (t/is (= {:value -9.0 :unit nil} (cfo/parse-token-value "-9"))))
  (t/testing "trims white-space"
    (t/is (= {:value -1.3 :unit nil} (cfo/parse-token-value "     -1.3   "))))
  (t/testing "parses unit: px"
    (t/is (= {:value 70.3 :unit "px"} (cfo/parse-token-value "     70.3px   "))))
  (t/testing "parses unit: %"
    (t/is (= {:value -10.0 :unit "%"} (cfo/parse-token-value "-10%"))))
  (t/testing "parses unit: px")
  (t/testing "returns nil for any invalid characters"
    (t/is (nil? (cfo/parse-token-value "     -1.3a   "))))
  (t/testing "doesnt accept invalid double"
    (t/is (nil? (cfo/parse-token-value ".3")))))

(t/deftest convert-dtcg-token-test
  (t/testing "keeps string scalar values untouched"
    (t/is (= {:name "spacing.16" :type :spacing :value "16"}
             (cfo/convert-dtcg-token {"name" "spacing.16" "type" "spacing" "value" "16"})))
    (t/is (= {:name "spacing.16" :type :spacing :value "16px"}
             (cfo/convert-dtcg-token {"name" "spacing.16" "type" "spacing" "value" "16px"}))))
  (t/testing "coerces numeric scalar values to strings"
    (t/is (= {:name "spacing.16" :type :spacing :value "16"}
             (cfo/convert-dtcg-token {"name" "spacing.16" "type" "spacing" "value" 16})))
    (t/is (= {:name "radius" :type :border-radius :value "4"}
             (cfo/convert-dtcg-token {"name" "radius" "type" "borderRadius" "value" 4})))))

(t/deftest token-applied-test
  (t/testing "matches passed token with `:token-attributes`"
    (t/is (true? (cfo/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match empty token"
    (t/is (nil? (cfo/token-applied? {} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "does't match passed token `:id`"
    (t/is (nil? (cfo/token-applied? {:name "b"} {:applied-tokens {:x "a"}} #{:x}))))
  (t/testing "doesn't match passed `:token-attributes`"
    (t/is (nil? (cfo/token-applied? {:name "a"} {:applied-tokens {:x "a"}} #{:y})))))

(t/deftest shapes-ids-by-applied-attributes
  (t/testing "Returns set of matched attributes that fit the applied token"
    (let [attributes #{:x :y :z}
          shape-applied-x {:id "shape-applied-x"
                           :applied-tokens {:x "1"}}
          shape-applied-y {:id "shape-applied-y"
                           :applied-tokens {:y "1"}}
          shape-applied-x-y {:id "shape-applied-x-y"
                             :applied-tokens {:x "1" :y "1"}}
          shape-applied-none {:id "shape-applied-none"
                              :applied-tokens {}}
          shape-applied-all {:id "shape-applied-all"
                             :applied-tokens {:x "1" :y "1" :z "1"}}
          shape-ids (fn [& xs] (into #{} (map :id xs)))
          shapes [shape-applied-x
                  shape-applied-y
                  shape-applied-x-y
                  shape-applied-all
                  shape-applied-none]
          expected (cfo/shapes-ids-by-applied-attributes {:name "1"} shapes attributes)]
      (t/is (= (:x expected) (shape-ids shape-applied-x
                                        shape-applied-x-y
                                        shape-applied-all)))
      (t/is (= (:y expected) (shape-ids shape-applied-y
                                        shape-applied-x-y
                                        shape-applied-all)))
      (t/is (= (:z expected) (shape-ids shape-applied-all)))
      (t/is (true? (cfo/shapes-applied-all? expected (shape-ids shape-applied-all) attributes)))
      (t/is (false? (cfo/shapes-applied-all? expected (apply shape-ids shapes) attributes)))
      (shape-ids shape-applied-x
                 shape-applied-x-y
                 shape-applied-all))))

(t/deftest tokens-applied-test
  (t/testing "is true when single shape matches the token and attributes"
    (t/is (true? (cfo/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                         {:applied-tokens {:x "b"}}]
                                            #{:x}))))
  (t/testing "is false when no shape matches the token or attributes"
    (t/is (nil? (cfo/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "b"}}
                                                        {:applied-tokens {:x "b"}}]
                                           #{:x})))
    (t/is (nil? (cfo/shapes-token-applied? {:name "a"} [{:applied-tokens {:x "a"}}
                                                        {:applied-tokens {:x "a"}}]
                                           #{:y})))))

;; Tokens lib

(t/deftest test-ensure-tokens-lib
  (t/testing "ensure-tokens-lib should add a tokens-lib and tokens-status to the file data if they are missing, and should not modify them if they already exist"
    (let [file       (thf/sample-file :file1)
          file-data  (ctf/file-data file)
          file-data' (cfo/ensure-tokens-lib file-data)]
      (t/is (contains? file-data' :tokens-lib))
      (t/is (ctob/tokens-lib? (:tokens-lib file-data')))
      (t/is (contains? file-data' :tokens-status))
      (t/is (ctos/tokens-status? (:tokens-status file-data'))))))

(t/deftest test-update-tokens-lib
  (t/testing "update when there is no tokens-lib has no effect"
    (let [file (thf/sample-file :file1)
          file' (cfo/update-tokens-lib file #(t/is false "This should not be called"))]
      (t/is (= file file'))))

  (t/testing "update a tokens-lib applies the changes correctly"
    (let [file (-> (thf/sample-file :file1)
                   (tht/add-tokens-lib))
          file' (ctf/update-file-data file
                                      #(cfo/update-tokens-lib
                                        %
                                        ctob/add-theme
                                        (ctob/make-token-theme :id (thi/new-id! :theme1)
                                                               :name "theme 1")))

          tokens-lib' (tht/get-tokens-lib file')]
      (t/is (= 2 (ctob/theme-count tokens-lib')))  ;; Count the hidden theme
      (t/is (ctob/token-theme? (ctob/get-theme tokens-lib' (thi/id :theme1)))))))

(t/deftest test-update-tokens-status
  (t/testing "update when there is no tokens-status has no effect"
    (let [file (thf/sample-file :file1)
          file' (cfo/update-tokens-status file #(t/is false "This should not be called"))]
      (t/is (= file file'))))

  (t/testing "update a tokens-status applies the changes correctly"
    (let [file (-> (thf/sample-file :file1)
                   (tht/add-tokens-lib)
                   (tht/update-tokens-lib
                    (fn [tokens-lib]
                      (-> tokens-lib
                          (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme1) :name "theme"))))))
          file' (ctf/update-file-data file
                                      #(cfo/update-tokens-status
                                        %
                                        ctos/set-tokens-status
                                        #{(thi/id :theme1)}
                                        #{}))
          tokens-status' (tht/get-tokens-status file')]
      (t/is (= 1 (count (ctos/get-active-theme-ids tokens-status'))))
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme1)))
      (t/is (= 0 (count (ctos/get-active-set-ids tokens-status')))))))

;; Tokens status with tokens lib

;; This is a private function but it deserves specific tests for all use cases
(def calculate-active-sets #'app.common.files.tokens/calculate-active-sets)

(t/deftest test-calculate-active-sets
  (t/testing "returns union of sets from all active themes"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b) :name "set-b"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-c) :name "set-c"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a" "set-b"}))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                :name "theme-2"
                                                                :group ""
                                                                :sets #{"set-c"})))]
      (t/is (= #{(thi/id :set-a) (thi/id :set-b) (thi/id :set-c)}
               (calculate-active-sets #{(thi/id :theme-1) (thi/id :theme-2)} tokens-lib)))))

  (t/testing "deduplicates sets shared by multiple active themes"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b) :name "set-b"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a" "set-b"}))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                :name "theme-2"
                                                                :group ""
                                                                :sets #{"set-b"})))]
      (t/is (= #{(thi/id :set-a) (thi/id :set-b)}
               (calculate-active-sets #{(thi/id :theme-1) (thi/id :theme-2)} tokens-lib)))))

  (t/testing "returns empty set when active-theme-ids is empty"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))]
      (t/is (= #{}
               (calculate-active-sets #{} tokens-lib)))))

  (t/testing "ignores active themes that do not exist in the lib"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))]
      (t/is (= #{(thi/id :set-a)}
               (calculate-active-sets #{(thi/id :theme-1) (thi/new-id! :nonexistent)} tokens-lib)))))

  (t/testing "ignores set names that do not exist in the lib"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a" "nonexistent-set"})))]
      (t/is (contains? (calculate-active-sets #{(thi/id :theme-1)} tokens-lib) (thi/id :set-a)))
      (t/is (= 2 (count (calculate-active-sets #{(thi/id :theme-1)} tokens-lib))))))

  (t/testing "returns empty set when themes have no sets"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{})))]
      (t/is (= #{}
               (calculate-active-sets #{(thi/id :theme-1)} tokens-lib)))))

  (t/testing "returns empty set when there are no themes"
    (let [tokens-lib (-> (ctob/make-tokens-lib))]
      (t/is (= #{}
               (calculate-active-sets #{} tokens-lib))))))

(t/deftest test-get-active-themes
  (t/testing "returns only themes that are active in the status"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1) :name "theme-1" :group ""))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2) :name "theme-2" :group ""))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-3) :name "theme-3" :group "")))
          tokens-status (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1) (thi/id :theme-3)}
                                                 :active-set-ids #{})]
      (t/is (= 2 (count (cfo/get-active-themes tokens-status tokens-lib))))
      (let [active-ids (into #{} (map ctob/get-id) (cfo/get-active-themes tokens-status tokens-lib))]
        (t/is (contains? active-ids (thi/id :theme-1)))
        (t/is (contains? active-ids (thi/id :theme-3)))
        (t/is (not (contains? active-ids (thi/id :theme-2)))))))

  (t/testing "returns empty sequence when no themes are active"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1) :name "theme-1" :group "")))
          tokens-status (ctos/make-tokens-status :active-theme-ids #{}
                                                 :active-set-ids #{})]
      (t/is (empty? (cfo/get-active-themes tokens-status tokens-lib)))))

  (t/testing "returns empty sequence when tokens-lib has no themes"
    (let [tokens-lib    (ctob/make-tokens-lib)
          tokens-status (ctos/make-tokens-status)]
      (t/is (empty? (cfo/get-active-themes tokens-status tokens-lib)))))

  (t/testing "active theme ids in status that do not exist in lib are silently ignored"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1) :name "theme-1" :group "")))
          tokens-status (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1) (thi/new-id! :nonexistent)}
                                                 :active-set-ids #{})]
      (t/is (= 1 (count (cfo/get-active-themes tokens-status tokens-lib)))))))

(t/deftest test-activate-theme
  (t/testing "activating a theme sets it as active"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/activate-theme tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-1)))))

  (t/testing "activating a theme deactivates other themes in the same group"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b) :name "set-b"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group "colors"
                                                                :sets #{"set-a"}))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                :name "theme-2"
                                                                :group "colors"
                                                                :sets #{"set-b"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/activate-theme tokens-status tokens-lib (thi/id :theme-2))]
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-2)))
      (t/is (not (ctos/theme-active? tokens-status' (thi/id :theme-1))))))

  (t/testing "themes in different groups can be active simultaneously"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b) :name "set-b"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group "colors"
                                                                :sets #{"set-a"}))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                :name "theme-2"
                                                                :group "spacing"
                                                                :sets #{"set-b"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/activate-theme tokens-status tokens-lib (thi/id :theme-2))]
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-1)))
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-2)))))

  (t/testing "activating an already-active theme returns status unchanged"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/activate-theme tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (identical? tokens-status tokens-status'))))

  (t/testing "activating a non-existent theme returns status unchanged"
    (let [tokens-lib (ctob/make-tokens-lib)
          tokens-status (ctos/make-tokens-status)
          tokens-status' (cfo/activate-theme tokens-status tokens-lib (thi/new-id! :nonexistent))]
      (t/is (identical? tokens-status tokens-status')))))

(t/deftest test-deactivate-theme
  (t/testing "deactivating an active theme removes it from active themes"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/deactivate-theme tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (not (ctos/theme-active? tokens-status' (thi/id :theme-1))))))

  (t/testing "deactivating a theme removes sets not used by remaining active themes"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b) :name "set-b"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"}))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                :name "theme-2"
                                                                :group ""
                                                                :sets #{"set-b"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1) (thi/id :theme-2)}
                                                  :active-set-ids #{(thi/id :set-a) (thi/id :set-b)})
          tokens-status' (cfo/deactivate-theme tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (not (ctos/theme-active? tokens-status' (thi/id :theme-1))))
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-2)))))

  (t/testing "deactivating an already-inactive theme returns status unchanged"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group "")))
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/deactivate-theme tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (identical? tokens-status tokens-status'))))

  (t/testing "deactivating a non-existent theme returns status unchanged"
    (let [tokens-lib (ctob/make-tokens-lib)
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/deactivate-theme tokens-status tokens-lib (thi/new-id! :nonexistent))]
      (t/is (identical? tokens-status tokens-status')))))

(t/deftest test-sync-tokens-status-with-lib
  (t/testing "removes theme ids that no longer exist in the lib"
    (let [theme-1-id (thi/new-id! :theme-1)
          set-a-id   (thi/new-id! :set-a)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id (thi/new-id! :removed-theme)}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
      (t/is (= #{theme-1-id} (ctos/get-active-theme-ids tokens-status')))
      (t/is (ctos/theme-active? tokens-status' theme-1-id))))

  (t/testing "removes set ids that no longer exist in the lib"
    (let [theme-1-id (thi/new-id! :theme-1)
          set-a-id   (thi/new-id! :set-a)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id (thi/new-id! :removed-set)})
          tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
      (t/is (= #{set-a-id} (ctos/get-active-set-ids tokens-status')))))

  (t/testing "returns same status object when everything is valid"
    (let [theme-1-id (thi/new-id! :theme-1)
          set-a-id   (thi/new-id! :set-a)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
      (t/is (identical? tokens-status tokens-status'))))

  (t/testing "returns same status object when both theme and set ids are empty"
    (let [tokens-lib     (ctob/make-tokens-lib)
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
      (t/is (identical? tokens-status tokens-status'))))

  (t/testing "removes all themes and sets when lib is empty"
    (let [tokens-lib     (ctob/make-tokens-lib)
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/new-id! :old-theme)}
                                                  :active-set-ids #{(thi/new-id! :old-set)})
          tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))
      (t/is (= #{} (ctos/get-active-set-ids tokens-status'))))))
