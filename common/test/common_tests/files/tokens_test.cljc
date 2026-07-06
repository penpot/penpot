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
   [app.common.uuid :as uuid]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

;; Helper functions

(t/deftest test-token-circular-reference?
  (t/testing "returns nil when token has no references"
    (let [tokens {"spacing" {:name "spacing" :value "16px"}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "spacing")))))

  (t/testing "returns nil for a simple non-circular reference chain"
    (let [tokens {"base"  {:name "base"  :value "10"}
                  "large" {:name "large" :value "{base}"}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "large")))))

  (t/testing "detects direct self-reference"
    (let [tokens {"self" {:name "self" :value "{self}"}}]
      (t/is (= "self" (cfo/token-circular-reference? tokens "self")))))

  (t/testing "detects indirect circular reference"
    (let [tokens {"a" {:name "a" :value "{b}"}
                  "b" {:name "b" :value "{c}"}
                  "c" {:name "c" :value "{a}"}}]
      (t/is (some? (cfo/token-circular-reference? tokens "a")))))

  (t/testing "returns nil for diamond dependency (no cycle)"
    (let [tokens {"base"    {:name "base"    :value "10"}
                  "left"    {:name "left"    :value "{base}"}
                  "right"   {:name "right"   :value "{base}"}
                  "combine" {:name "combine" :value "{left} {right}"}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "combine")))))

  (t/testing "returns nil when token name does not exist in tokens map"
    (let [tokens {"a" {:name "a" :value "10"}}]
      (t/is (nil? (cfo/token-circular-reference? tokens "nonexistent")))))

  (t/testing "detects circular reference in map-typed values (typography composite)"
    (let [tokens {"family" {:name "family" :value "Arial"}
                  "size"   {:name "size"   :value "16px"}
                  "typog"  {:name "typog"  :value {:font-family {:reference "{family}"}
                                                   :font-size   {:reference "{size}"}}}
                  "cycle"  {:name "cycle"  :value {:font-family {:reference "{typog}"}}}}]
      ;; typog references family and size (no cycle)
      (t/is (nil? (cfo/token-circular-reference? tokens "typog"))))))

(t/deftest test-attributes-map
  (t/testing "creates map from attributes to token name"
    (t/is (= {:fill "primary" :stroke "primary"}
             (cfo/attributes-map [:fill :stroke] {:name "primary"}))))

  (t/testing "returns empty map for empty attributes"
    (t/is (= {} (cfo/attributes-map [] {:name "primary"}))))

  (t/testing "maps each attribute independently"
    (t/is (= {:x "t" :y "t" :z "t"}
             (cfo/attributes-map [:x :y :z] {:name "t"})))))

(t/deftest test-remove-attributes-for-token
  (t/testing "removes entries matching both attribute set and token name"
    (t/is (= {:stroke "other"}
             (cfo/remove-attributes-for-token
              [:fill :stroke] "primary"
              {:fill "primary" :stroke "other"}))))

  (t/testing "keeps entries with matching attribute but different token name"
    (t/is (= {:fill "secondary" :stroke "other"}
             (cfo/remove-attributes-for-token
              [:fill :stroke] "primary"
              {:fill "secondary" :stroke "other"}))))

  (t/testing "keeps entries with matching token name but non-matching attribute"
    (t/is (= {:opacity "primary"}
             (cfo/remove-attributes-for-token
              [:fill :stroke] "primary"
              {:fill "primary" :opacity "primary"}))))

  (t/testing "returns empty map when all entries match"
    (t/is (= {}
             (cfo/remove-attributes-for-token
              [:fill] "primary"
              {:fill "primary"}))))

  (t/testing "returns empty map for empty applied-tokens"
    (t/is (= {}
             (cfo/remove-attributes-for-token [:fill] "primary" {})))))

(t/deftest test-color-token?
  (t/testing "returns true for color type"
    (t/is (true? (cfo/color-token? {:type :color}))))

  (t/testing "returns false for non-color types"
    (t/is (false? (cfo/color-token? {:type :spacing})))
    (t/is (false? (cfo/color-token? {:type :border-radius})))
    (t/is (false? (cfo/color-token? {})))))

(t/deftest test-is-reference?
  (t/testing "returns truthy when value contains opening brace"
    (t/is (cfo/is-reference? {:value "{foo}"}))
    (t/is (cfo/is-reference? {:value "calc({bar} + 1)"})))

  (t/testing "returns falsy when value has no brace"
    (t/is (not (cfo/is-reference? {:value "16px"})))
    (t/is (not (cfo/is-reference? {:value ""})))))

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

;; Tokens lib in file data

(t/deftest test-ensure-tokens-lib
  (t/testing "ensure-tokens-lib should add a tokens-lib and tokens-status to the file data if they are missing"
    (let [file       (thf/sample-file :file1)
          file-data  (ctf/file-data file)
          file-data' (cfo/ensure-tokens-lib file-data)]
      (t/is (contains? file-data' :tokens-lib))
      (t/is (ctob/tokens-lib? (:tokens-lib file-data')))
      (t/is (contains? file-data' :tokens-status))
      (t/is (ctos/tokens-status? (:tokens-status file-data')))))

  (t/testing "ensure-tokens-lib should add a tokens-lib to the file data if it's missing"
    (let [tokens-status (ctos/make-tokens-status)
          file          (-> (thf/sample-file :file1)
                            (assoc-in [:data :tokens-status] tokens-status))
          file-data     (ctf/file-data file)
          file-data'    (cfo/ensure-tokens-lib file-data)]
      (t/is (contains? file-data' :tokens-lib))
      (t/is (ctob/tokens-lib? (:tokens-lib file-data')))
      (t/is (= tokens-status (:tokens-status file-data')))))

  (t/testing "ensure-tokens-lib should add a tokens-status to the file data if it's missing"
    (let [tokens-lib (ctob/make-tokens-lib)
          file       (-> (thf/sample-file :file1)
                         (assoc-in [:data :tokens-lib] tokens-lib))
          file-data  (ctf/file-data file)
          file-data' (cfo/ensure-tokens-lib file-data)]
      (t/is (= tokens-lib (:tokens-lib file-data')))
      (t/is (contains? file-data' :tokens-status))
      (t/is (ctos/tokens-status? (:tokens-status file-data')))))

  (t/testing "ensure-tokens-lib should not add a tokens-lib if there is a tokens-file"
    (let [tokens-file   (uuid/next)
          tokens-status (ctos/make-tokens-status)
          file          (-> (thf/sample-file :file1)
                            (assoc-in [:data :tokens-file] tokens-file)
                            (assoc-in [:data :tokens-status] tokens-status))
          file-data     (ctf/file-data file)
          file-data'    (cfo/ensure-tokens-lib file-data)]
      (t/is (not (contains? file-data' :tokens-lib)))
      (t/is (= tokens-file (:tokens-file file-data')))
      (t/is (= tokens-status (:tokens-status file-data')))))

  (t/testing "ensure-tokens-lib should not add a tokens-lib if there is a tokens-file, but should add a tokens-status if it's missing"
    (let [tokens-file (uuid/next)
          file        (-> (thf/sample-file :file1)
                          (assoc-in [:data :tokens-file] tokens-file))
          file-data   (ctf/file-data file)
          file-data'  (cfo/ensure-tokens-lib file-data)]
      (t/is (not (contains? file-data' :tokens-lib)))
      (t/is (= tokens-file (:tokens-file file-data')))
      (t/is (contains? file-data' :tokens-status))
      (t/is (ctos/tokens-status? (:tokens-status file-data'))))))

(t/deftest test-get-tokens-lib
  (t/testing "returns tokens-lib from file data"
    (let [tokens-lib (ctob/make-tokens-lib)
          file-data  {:tokens-lib tokens-lib}]
      (t/is (= tokens-lib (cfo/get-tokens-lib file-data)))))

  (t/testing "returns nil when no tokens-lib"
    (t/is (nil? (cfo/get-tokens-lib {})))))

(t/deftest test-get-tokens-file
  (t/testing "returns tokens-file from file data"
    (let [file-id   (thi/new-id! :tokens-file)
          file-data {:tokens-file file-id}]
      (t/is (= file-id (cfo/get-tokens-file file-data)))))

  (t/testing "returns nil when no tokens-file"
    (t/is (nil? (cfo/get-tokens-file {})))))

(t/deftest test-set-tokens-file
  (t/testing "sets tokens-file on file data"
    (let [file-id    (thi/new-id! :tokens-file)
          file-data  {:other :data}
          file-data' (cfo/set-tokens-file file-data file-id)]
      (t/is (= file-id (:tokens-file file-data')))
      (t/is (= :data (:other file-data'))))))

(t/deftest test-get-tokens-status
  (t/testing "returns tokens-status from file data"
    (let [tokens-status (ctos/make-tokens-status)
          file-data     {:tokens-status tokens-status}]
      (t/is (= tokens-status (cfo/get-tokens-status file-data)))))

  (t/testing "returns nil when no tokens-status"
    (t/is (nil? (cfo/get-tokens-status {})))))

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

(t/deftest test-toggle-theme-active
  (t/testing "activates an inactive theme"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/toggle-theme-active tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-1)))))

  (t/testing "deactivates an active theme"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/toggle-theme-active tokens-status tokens-lib (thi/id :theme-1))]
      (t/is (not (ctos/theme-active? tokens-status' (thi/id :theme-1)))))))

(t/deftest test-set-theme-active
  (t/testing "activates a theme when active? is true"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/set-theme-active tokens-status tokens-lib (thi/id :theme-1) true)]
      (t/is (ctos/theme-active? tokens-status' (thi/id :theme-1)))))

  (t/testing "deactivates a theme when active? is false"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{(thi/id :theme-1)}
                                                  :active-set-ids #{(thi/id :set-a)})
          tokens-status' (cfo/set-theme-active tokens-status tokens-lib (thi/id :theme-1) false)]
      (t/is (not (ctos/theme-active? tokens-status' (thi/id :theme-1)))))))

(t/deftest test-get-active-sets
  (t/testing "returns active sets resolved from status and lib"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b")))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id})
          active-sets   (cfo/get-active-sets tokens-status tokens-lib)]
      (t/is (= 1 (count active-sets)))
      (t/is (= set-a-id (ctob/get-id (first active-sets))))))

  (t/testing "returns empty set when no sets are active"
    (let [tokens-lib    (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a")))
          tokens-status (ctos/make-tokens-status)
          active-sets   (cfo/get-active-sets tokens-status tokens-lib)]
      (t/is (empty? active-sets)))))

(t/deftest test-set-set-active
  (t/testing "activates a set and clears all themes"
    (let [set-a-id     (thi/new-id! :set-a)
          theme-1-id   (thi/new-id! :theme-1)
          tokens-lib   (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                           (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                  :name "theme-1"
                                                                  :group ""
                                                                  :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{})
          tokens-status' (cfo/set-set-active tokens-status tokens-lib set-a-id true)]
      (t/is (ctos/set-active? tokens-status' set-a-id))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))))

  (t/testing "deactivates a set and clears all themes"
    (let [set-a-id     (thi/new-id! :set-a)
          theme-1-id   (thi/new-id! :theme-1)
          tokens-lib   (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                           (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                  :name "theme-1"
                                                                  :group ""
                                                                  :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/set-set-active tokens-status tokens-lib set-a-id false)]
      (t/is (not (ctos/set-active? tokens-status' set-a-id)))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))))

  (t/testing "returns same status when set is already in target state"
    (let [set-a-id   (thi/new-id! :set-a)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a")))
          tokens-status  (ctos/make-tokens-status :active-set-ids #{set-a-id})
          tokens-status' (cfo/set-set-active tokens-status tokens-lib set-a-id true)]
      (t/is (identical? tokens-status tokens-status'))))

  (t/testing "returns same status for non-existent set"
    (let [tokens-lib   (ctob/make-tokens-lib)
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/set-set-active tokens-status tokens-lib (thi/new-id! :nonexistent) true)]
      (t/is (identical? tokens-status tokens-status')))))

(t/deftest test-activate-set
  (t/testing "delegates to set-set-active with true"
    (let [set-a-id   (thi/new-id! :set-a)
          theme-1-id (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status)
          tokens-status' (cfo/activate-set tokens-status tokens-lib set-a-id)]
      (t/is (ctos/set-active? tokens-status' set-a-id))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status'))))))

(t/deftest test-deactivate-set
  (t/testing "delegates to set-set-active with false"
    (let [set-a-id   (thi/new-id! :set-a)
          theme-1-id   (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/deactivate-set tokens-status tokens-lib set-a-id)]
      (t/is (not (ctos/set-active? tokens-status' set-a-id)))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status'))))))

(t/deftest test-toggle-set-active
  (t/testing "activates an inactive set"
    (let [set-a-id   (thi/new-id! :set-a)
          theme-1-id (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{})
          tokens-status' (cfo/toggle-set-active tokens-status tokens-lib set-a-id)]
      (t/is (ctos/set-active? tokens-status' set-a-id))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))))

  (t/testing "deactivates an active set"
    (let [set-a-id   (thi/new-id! :set-a)
          theme-1-id (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/toggle-set-active tokens-status tokens-lib set-a-id)]
      (t/is (not (ctos/set-active? tokens-status' set-a-id)))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status'))))))

(t/deftest test-sets-at-path-all-active?
  (t/testing "returns :all when all sets at path are active"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b")))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id set-b-id})]
      (t/is (= :all (cfo/sets-at-path-all-active? tokens-status tokens-lib ["group"])))))

  (t/testing "returns :none when no sets at path are active"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b")))
          tokens-status (ctos/make-tokens-status :active-set-ids #{})]
      (t/is (= :none (cfo/sets-at-path-all-active? tokens-status tokens-lib ["group"])))))

  (t/testing "returns :partial when some sets at path are active"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b")))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id})]
      (t/is (= :partial (cfo/sets-at-path-all-active? tokens-status tokens-lib ["group"])))))

  (t/testing "returns :none for empty path when no sets exist at root"
    (let [tokens-lib    (ctob/make-tokens-lib)
          tokens-status (ctos/make-tokens-status)]
      (t/is (= :none (cfo/sets-at-path-all-active? tokens-status tokens-lib [])))))

  (t/testing "several combinations with longer paths"
    (let [baz-id      (thi/new-id! :baz)
          bam-id      (thi/new-id! :bam)
          none-id     (thi/new-id! :none)
          partial-id  (thi/new-id! :partial)
          all-id      (thi/new-id! :all)
          invalid-id  (thi/new-id! :invalid)
          tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :id baz-id :name "foo/bar/baz"))
                          (ctob/add-set (ctob/make-token-set :id bam-id :name "foo/bar/bam"))
                          (ctob/add-theme (ctob/make-token-theme :id none-id
                                                                 :name "none"))
                          (ctob/add-theme (ctob/make-token-theme :id partial-id
                                                                 :name "partial"
                                                                 :sets #{"foo/bar/baz"}))
                          (ctob/add-theme (ctob/make-token-theme :id all-id
                                                                 :name "all"
                                                                 :sets #{"foo/bar/baz"
                                                                         "foo/bar/bam"}))
                          (ctob/add-theme (ctob/make-token-theme :id invalid-id
                                                                 :name "invalid"
                                                                 :sets #{"foo/missing"})))

          expected-none (-> (ctos/make-tokens-status {:active-theme-ids #{none-id}
                                                      :active-set-ids #{}})
                            (cfo/sets-at-path-all-active? tokens-lib ["foo"]))
          expected-all (-> (ctos/make-tokens-status {:active-theme-ids #{all-id}
                                                     :active-set-ids #{baz-id bam-id}})
                           (cfo/sets-at-path-all-active? tokens-lib ["foo"]))
          expected-partial (-> (ctos/make-tokens-status {:active-theme-ids #{partial-id}
                                                         :active-set-ids #{baz-id}})
                               (cfo/sets-at-path-all-active? tokens-lib ["foo"]))
          expected-invalid-none (-> (ctos/make-tokens-status {:active-theme-ids #{invalid-id}
                                                              :active-set-ids #{}})
                                    (cfo/sets-at-path-all-active? tokens-lib ["foo"]))]
      (t/is (= :none expected-none))
      (t/is (= :all expected-all))
      (t/is (= :partial expected-partial))
      (t/is (= :none expected-invalid-none)))))

(t/deftest test-toggle-set-group-active
  (t/testing "activates all sets in group when none are active, and deactivate all themes"
    (let [set-a-id   (thi/new-id! :set-a)
          set-b-id   (thi/new-id! :set-b)
          set-c-id   (thi/new-id! :set-c)
          theme-1-id (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b"))
                         (ctob/add-set (ctob/make-token-set :id set-c-id :name "group/set-b/child"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{})
          tokens-status' (cfo/toggle-set-group-active tokens-status tokens-lib ["group"])]
      (t/is (ctos/set-active? tokens-status' set-a-id))
      (t/is (ctos/set-active? tokens-status' set-b-id))
      (t/is (ctos/set-active? tokens-status' set-c-id))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))))

  (t/testing "deactivates all sets in group when all are active, and deactivate all themes"
    (let [set-a-id   (thi/new-id! :set-a)
          set-b-id   (thi/new-id! :set-b)
          set-c-id   (thi/new-id! :set-c)
          theme-1-id (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b"))
                         (ctob/add-set (ctob/make-token-set :id set-c-id :name "group/set-b/child"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"group/set-a" "group/set-b" "group/set-b/child"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id set-b-id set-c-id})
          tokens-status' (cfo/toggle-set-group-active tokens-status tokens-lib ["group"])]
      (t/is (not (ctos/set-active? tokens-status' set-a-id)))
      (t/is (not (ctos/set-active? tokens-status' set-b-id)))
      (t/is (not (ctos/set-active? tokens-status' set-c-id)))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status')))))

  (t/testing "deactivates all sets in group when only some are active, and deactivate all themes"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          set-c-id (thi/new-id! :set-c)
          theme-1-id   (thi/new-id! :theme-1)
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "group/set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "group/set-b"))
                         (ctob/add-set (ctob/make-token-set :id set-c-id :name "group/set-b/child"))
                         (ctob/add-theme (ctob/make-token-theme :id theme-1-id
                                                                :name "theme-1"
                                                                :group ""
                                                                :sets #{"set-a"})))
          tokens-status  (ctos/make-tokens-status :active-theme-ids #{theme-1-id}
                                                  :active-set-ids #{set-a-id})
          tokens-status' (cfo/toggle-set-group-active tokens-status tokens-lib ["group"])]
      (t/is (not (ctos/set-active? tokens-status' set-a-id)))
      (t/is (not (ctos/set-active? tokens-status' set-b-id)))
      (t/is (not (ctos/set-active? tokens-status' set-c-id)))
      (t/is (= #{} (ctos/get-active-theme-ids tokens-status'))))))

(t/deftest test-get-tokens-in-active-sets
  (t/testing "returns merged tokens from active sets in set order"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tok-a    (ctob/make-token :name "spacing" :type :spacing :value "8px")
          tok-b    (ctob/make-token :name "radius"  :type :border-radius :value "4px")
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b"))
                         (ctob/add-token set-a-id tok-a)
                         (ctob/add-token set-b-id tok-b))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id set-b-id})
          tokens        (cfo/get-tokens-in-active-sets tokens-status tokens-lib)]
      (t/is (= 2 (count tokens)))
      (t/is (contains? tokens "spacing"))
      (t/is (contains? tokens "radius"))))

  (t/testing "later active sets override earlier sets with same token name"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tok-a    (ctob/make-token :name "spacing" :type :spacing :value "8px")
          tok-b    (ctob/make-token :name "spacing" :type :spacing :value "16px")
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b"))
                         (ctob/add-token set-a-id tok-a)
                         (ctob/add-token set-b-id tok-b))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id set-b-id})
          tokens        (cfo/get-tokens-in-active-sets tokens-status tokens-lib)]
      (t/is (= 1 (count tokens)))
      (t/is (= "16px" (:value (get tokens "spacing"))))))

  (t/testing "tokens in inactive sets are not included"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tok-a    (ctob/make-token :name "spacing" :type :spacing :value "8px")
          tok-b    (ctob/make-token :name "spacing" :type :spacing :value "16px")
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b"))
                         (ctob/add-token set-a-id tok-a)
                         (ctob/add-token set-b-id tok-b))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id})
          tokens        (cfo/get-tokens-in-active-sets tokens-status tokens-lib)]
      (t/is (= 1 (count tokens)))
      (t/is (= "8px" (:value (get tokens "spacing"))))))

  (t/testing "returns empty map when no sets are active"
    (let [tokens-lib    (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a) :name "set-a")))
          tokens-status (ctos/make-tokens-status)
          tokens        (cfo/get-tokens-in-active-sets tokens-status tokens-lib)]
      (t/is (empty? tokens))))

  (t/testing "returns empty map when empty lib"
    (let [tokens-lib    (ctob/make-tokens-lib)
          tokens-status (ctos/make-tokens-status)
          tokens        (cfo/get-tokens-in-active-sets tokens-status tokens-lib)]
      (t/is (empty? tokens)))))

(t/deftest test-get-tokens-in-active-sets-force
  (t/testing "includes force-set-id even when not in active sets"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tok-a    (ctob/make-token :name "spacing" :type :spacing :value "8px")
          tok-b    (ctob/make-token :name "radius"  :type :border-radius :value "4px")
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b"))
                         (ctob/add-token set-a-id tok-a)
                         (ctob/add-token set-b-id tok-b))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id})
          tokens        (cfo/get-tokens-in-active-sets-force tokens-status tokens-lib set-b-id)]
      (t/is (= 2 (count tokens)))
      (t/is (contains? tokens "spacing"))
      (t/is (contains? tokens "radius"))))

  (t/testing "active set tokens override force-set tokens with same name"
    (let [set-a-id (thi/new-id! :set-a)
          set-b-id (thi/new-id! :set-b)
          tok-a    (ctob/make-token :name "spacing" :type :spacing :value "8px")
          tok-b    (ctob/make-token :name "spacing" :type :spacing :value "32px")
          tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :id set-a-id :name "set-a"))
                         (ctob/add-set (ctob/make-token-set :id set-b-id :name "set-b"))
                         (ctob/add-token set-a-id tok-a)
                         (ctob/add-token set-b-id tok-b))
          tokens-status (ctos/make-tokens-status :active-set-ids #{set-a-id})
          tokens        (cfo/get-tokens-in-active-sets-force tokens-status tokens-lib set-b-id)]
      (t/is (= 1 (count tokens)))
      (t/is (= "8px" (:value (get tokens "spacing")))))))

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
