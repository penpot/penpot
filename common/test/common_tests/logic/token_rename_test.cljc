;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.logic.token-rename-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.logic.tokens :as clt]
   [app.common.schema :as sm]
   [app.common.test-helpers.compositions :as tho]
   [app.common.test-helpers.files :as thf]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as ths]
   [app.common.test-helpers.tokens :as tht]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [clojure.test :as t]))

(t/use-fixtures :each thi/test-fixture)

(defn- add-tokens
  [lib set-label tokens]
  (reduce (fn [lib [name type value]]
            (ctob/add-token lib (thi/id set-label) (ctob/make-token {:name name :type type :value value})))
          lib
          tokens))

(defn- sample-file
  "A file with an active set `core` and an inactive set `dark`."
  [core dark]
  (tht/sample-file-with-tokens
   :lib-fn #(-> %
                (ctob/add-set (ctob/make-token-set :id (thi/new-id! :core) :name "core"))
                (ctob/add-set (ctob/make-token-set :id (thi/new-id! :dark) :name "dark"))
                (add-tokens :core core)
                (add-tokens :dark dark))
   :status-fn #(ctos/set-tokens-status % #{} #{(thi/id :core)})))

(defn- rename
  [file renames scope]
  (thf/apply-changes file (-> (pcb/empty-changes)
                              (pcb/with-library-data (:data file))
                              (clt/generate-rename-token-references renames {:scope scope}))))

(defn- rename-in-core
  "Renames tokens of the set `core`, as the workspace does before it
  renames the references to them."
  [file renames]
  (tht/update-tokens-lib file
                         (fn [lib]
                           (reduce-kv (fn [lib old-name new-name]
                                        (let [token (ctob/get-token-by-name lib "core" old-name)]
                                          (ctob/update-token lib (thi/id :core) (:id token) #(assoc % :name new-name))))
                                      lib
                                      renames))))

(defn- value
  [file set-name token-name]
  (:value (ctob/get-token-by-name (tht/get-tokens-lib file) set-name token-name)))

(t/deftest rename-token-values-test
  (let [file (sample-file [["size" :sizing "4"]
                           ["double" :sizing "{size} * 2 + {size}"]
                           ["size-large" :sizing "{size-large2}"]
                           ["size-large2" :sizing "{size} * 4"]]
                          [["double" :sizing "{size} * 3"]])]
    (t/testing "in every set"
      (let [file' (rename file {"size" "space"} :all)]
        (t/is (= "{space} * 2 + {space}" (value file' "core" "double")))
        (t/is (= "{space} * 4" (value file' "core" "size-large2")))
        (t/is (= "{space} * 3" (value file' "dark" "double")))
        (t/is (= "{size-large2}" (value file' "core" "size-large"))
              "a name starting like the renamed one")))
    (t/testing "in the active sets"
      (let [file' (rename file {"size" "space"} :active)]
        (t/is (= "{space} * 2 + {space}" (value file' "core" "double")))
        (t/is (= "{size} * 3" (value file' "dark" "double")))))
    (t/testing "in the given sets"
      (let [file' (rename file {"size" "space"} #{(thi/id :dark)})]
        (t/is (= "{size} * 2 + {size}" (value file' "core" "double")))
        (t/is (= "{space} * 3" (value file' "dark" "double")))))))

(t/deftest rename-token-values-by-reference-test
  (t/testing "a name with a `$`"
    (let [file' (-> (sample-file [["price$base" :number "4"]
                                  ["price" :number "{price$base} * 2"]]
                                 [])
                    (rename {"price$base" "price$start"} :all))]
      (t/is (= "{price$start} * 2" (value file' "core" "price")))))
  (t/testing "in composite values"
    (let [file' (-> (sample-file [["size" :font-size "16"]
                                  ["text" :typography {:font-size "{size}" :line-height "1.5"}]
                                  ["fonts" :font-family ["{brand}" "Inter"]]
                                  ["brand" :font-family "Roboto"]]
                                 [])
                    (rename {"size" "space" "brand" "brand-font"} :all))]
      (t/is (= {:font-size "{space}" :line-height "1.5"} (value file' "core" "text")))
      (t/is (= ["{brand-font}" "Inter"] (value file' "core" "fonts")))))
  (t/testing "a composite token, with references to its fields"
    (let [file' (-> (sample-file [["size" :font-size "16"]
                                  ["text" :typography {:font-size "{size}" :line-height "1.5"}]
                                  ["double" :font-size "{text.font-size} * 2"]]
                                 [])
                    (rename {"text" "heading"} :all))]
      (t/is (= "{heading.font-size} * 2" (value file' "core" "double")))))
  (t/testing "a nested group, also inside shadows"
    (let [renames {"color.primary" "brand.primary" "color.secondary.light" "brand.secondary.light"}
          file'   (-> (sample-file [["color.primary" :color "#ff0000"]
                                    ["color.secondary.light" :color "#00ff00"]
                                    ["accent" :color "{color.secondary.light}"]
                                    ["drop" :shadow [{:offset-x "0" :offset-y "2" :blur "4" :spread "0"
                                                      :color "{color.primary}" :inset false}]]]
                                   [["accent" :color "{color.primary}"]])
                      (rename renames :all))]
      (t/is (= "{brand.secondary.light}" (value file' "core" "accent")))
      (t/is (= "{brand.primary}" (value file' "dark" "accent")))
      (t/is (= "{brand.primary}" (:color (first (value file' "core" "drop")))))))
  (t/testing "several tokens at once, as for a group rename"
    (let [file' (-> (sample-file [["a" :number "1"] ["b" :number "2"] ["sum" :number "{a} + {b}"]] [])
                    (rename {"a" "b" "b" "c"} :all))]
      (t/is (= "{b} + {c}" (value file' "core" "sum"))
            "a reference to `a` is not renamed on to `c`"))))

(t/deftest rename-applied-tokens-test
  (t/testing "each shape keeps the attributes it had the token on"
    (let [file  (-> (sample-file [["sw-10" :stroke-width "10"]] [])
                    (tho/add-rect :top-rect)
                    (tho/add-rect :all-rect)
                    (tht/apply-token-to-shape :top-rect "sw-10" [:stroke-width-top] [] nil)
                    (tht/apply-token-to-shape :all-rect "sw-10" cto/stroke-width-keys [] nil))
          file' (rename file {"sw-10" "sw-big"} :all)]
      (t/is (= {:stroke-width-top "sw-big"}
               (:applied-tokens (ths/get-shape file' :top-rect))))
      (t/is (= (zipmap cto/stroke-width-keys (repeat "sw-big"))
               (:applied-tokens (ths/get-shape file' :all-rect)))))))

(defn- file-with-component-copy
  "A component and a copy of it, with `color.primary` applied to both, as
  after applying it to the main and syncing the copy."
  []
  (-> (sample-file [["color.primary" :color "#FF0000"]
                    ["color.secondary" :color "#00FF00"]]
                   [])
      (tho/add-simple-component-with-copy :component1
                                          :main-root
                                          :main-child
                                          :copy-root
                                          :copy-root-params {:children-labels [:copy-child]})
      (tht/apply-token-to-shape :main-child "color.primary" [:fill] [:fill] "#FF0000")
      (tht/apply-token-to-shape :copy-child "color.primary" [:fill] [:fill] "#FF0000")))

(t/deftest rename-applied-tokens-in-copies-test
  (let [renames {"color.primary" "colors.primary" "color.secondary" "colors.secondary"}
        file    (-> (file-with-component-copy)
                    (rename-in-core renames)
                    (rename renames :all))
        copy    (ths/get-shape file :copy-child)]
    (t/testing "renames the token on the main and the copy"
      (t/is (= "colors.primary" (get-in (ths/get-shape file :main-child) [:applied-tokens :fill])))
      (t/is (= "colors.primary" (get-in copy [:applied-tokens :fill]))))
    (t/testing "keeps the copy in sync with its main"
      ;; A touched copy would skip the token applied to the main next (#9495).
      (t/is (not (contains? (set (:touched copy)) :fill-group)))
      (let [file (-> file
                     (tht/apply-token-to-shape :main-child "colors.secondary" [:fill] [:fill] "#00FF00")
                     (tho/propagate-component-changes :component1))]
        (t/is (= "colors.secondary" (get-in (ths/get-shape file :copy-child) [:applied-tokens :fill])))))))

(defn- rename-tokens
  [file renames opts]
  (thf/apply-changes file (-> (pcb/empty-changes)
                              (pcb/with-library-data (:data file))
                              (clt/generate-rename-tokens (thi/id :core) renames opts))))

(defn- names
  [file set-name]
  (let [lib (tht/get-tokens-lib file)]
    (set (keys (ctob/get-tokens lib (ctob/get-id (ctob/get-set-by-name lib set-name)))))))

(def ^:private primary-group
  {:path "primary" :name "primary" :depth 0})

(defn- file-with-group
  []
  (sample-file [["primary.base" :color "#ff0000"]
                ["primary.primary-dark" :color "#aa0000"]
                ["primary.alias" :color "{primary.base}"]
                ["primary.size" :sizing "4"]
                ["primary-light" :color "#ffaaaa"]
                ["accent" :color "{primary.primary-dark}"]]
               [["accent" :color "{primary.base}"]]))

(t/deftest token-group-renames-test
  (let [tokens (ctob/get-tokens (tht/get-tokens-lib (file-with-group)) (thi/id :core))]
    (t/is (= {"primary.base" "main.base"
              "primary.primary-dark" "main.primary-dark"
              "primary.alias" "main.alias"}
             (ctob/token-group-renames tokens :color primary-group "main"))
          "the tokens of the type, by path segment")
    (t/is (= {"primary.size" "primary.gap"}
             (ctob/token-group-renames {"primary.size" (get tokens "primary.size")} :sizing
                                       {:path "primary.size" :name "size" :depth 1} "gap"))
          "a node below the top")))

(t/deftest rename-tokens-test
  (let [file    (-> (file-with-group)
                    (tho/add-rect :rect)
                    (tht/apply-token-to-shape :rect "primary.base" [:fill] [:fill] "#ff0000"))
        renames (ctob/token-group-renames (ctob/get-tokens (tht/get-tokens-lib file) (thi/id :core))
                                          :color primary-group "main")]
    (t/testing "without references"
      (let [file' (rename-tokens file renames {})]
        (t/is (= #{"main.base" "main.primary-dark" "main.alias" "primary.size" "primary-light" "accent"}
                 (names file' "core")))
        (t/is (= "{primary.base}" (value file' "core" "main.alias")))
        (t/is (= "primary.base" (get-in (ths/get-shape file' :rect) [:applied-tokens :fill])))))
    (t/testing "with references"
      (let [file' (rename-tokens file renames {:update-references true})]
        (t/is (= "{main.base}" (value file' "core" "main.alias"))
              "a token renamed and referencing a renamed one")
        (t/is (= "{main.primary-dark}" (value file' "core" "accent")))
        (t/is (= "{main.base}" (value file' "dark" "accent")))
        (t/is (= "main.base" (get-in (ths/get-shape file' :rect) [:applied-tokens :fill])))))
    (t/testing "in the active sets only"
      (let [file' (rename-tokens file renames {:update-references true :scope :active})]
        (t/is (= "{main.primary-dark}" (value file' "core" "accent")))
        (t/is (= "{primary.base}" (value file' "dark" "accent")))))
    (t/testing "onto a name another token of the set has, or a group"
      (let [error (fn [renames]
                    (try (rename-tokens file renames {}) nil
                         (catch #?(:clj Exception :cljs :default) e (ex-data e))))]
        (t/is (= :name-collision (:error (error {"primary-light" "accent"}))))
        (t/is (= [[:processor/token-name-collision "primary"]]
                 (->> (error {"primary-light" "primary"}) :errors (mapv (juxt :code (comp :parent :data))))))))))

(t/deftest rename-tokens-scope-test
  (let [file  (sample-file [["size" :sizing "4"] ["double" :sizing "{size} * 2"]]
                           [["gap" :sizing "2"] ["half" :sizing "{size} / 2"]])
        error (fn [renames scope]
                (try (rename-tokens file renames {:scope scope}) nil
                     (catch #?(:clj Exception :cljs :default) e (:error (ex-data e)))))]
    (t/testing "the names are checked in the sets the rename reaches"
      (t/is (= :name-collision (error {"size" "gap.x"} :all)))
      (t/is (nil? (error {"size" "gap.x"} :set)))
      (t/is (nil? (error {"size" "gap.x"} :active)) "the other set is inactive"))
    (t/testing "the references follow in those sets"
      (let [file' (rename-tokens file {"size" "space"} {:update-references true :scope :set})]
        (t/is (= "{space} * 2" (value file' "core" "double")))
        (t/is (= "{size} / 2" (value file' "dark" "half"))))
      (let [file' (rename-tokens file {"size" "space"} {:update-references true :scope :all})]
        (t/is (= "{space} / 2" (value file' "dark" "half")))))))

(t/deftest group-name-schema-test
  (let [file   (sample-file [["x.a" :color "#f00"] ["x.b" :color "#0f0"] ["y.a" :color "#00f"] ["z" :color "#000"]]
                            [["w" :color "#fff"]])
        node   {:path "x" :name "x" :depth 0}
        valid? (fn [name duplicate? scope]
                 (sm/validate (cfo/make-group-name-schema (:data file) (thi/id :core) :color node duplicate? scope)
                              {:name name}))]
    (t/is (valid? "v" false :all))
    (t/is (not (valid? "y" false :all)) "one of the moved tokens takes a name")
    (t/is (not (valid? "z" false :all)) "below a token")
    (t/is (not (valid? "w" false :all)) "below a token of another set the rename reaches")
    (t/is (valid? "w" false :set) "a set the rename doesn't reach")
    (t/is (valid? "w" false :active) "an inactive set")
    (t/is (valid? "v" true :set))
    (t/is (not (valid? "y" true :set)))))

(t/deftest token-rename-check-test
  (let [file   (sample-file [["size" :sizing "4"] ["space" :sizing "8"]]
                            [["gap" :sizing "2"]])
        schema (fn [scope]
                 (cfo/with-token-rename-check [:map [:name :string]] (:data file) (thi/id :core) "size" scope))]
    (t/is (sm/validate (schema :all) {:name "size"}) "the name unchanged")
    (t/is (sm/validate (schema :all) {:name "gap"}) "a name another set has")
    (t/is (not (sm/validate (schema :all) {:name "space"})) "a name of the set")
    (t/is (not (sm/validate (schema :all) {:name "gap.x"})) "below a token of another set")
    (t/is (sm/validate (schema :set) {:name "gap.x"}) "a set the rename doesn't reach")))

(t/deftest rename-scope-choice-test
  (t/is (cfo/rename-scope-choice? (tht/get-tokens-lib (sample-file [] [])))
        "two sets")
  (t/is (not (cfo/rename-scope-choice?
              (-> (ctob/make-tokens-lib)
                  (ctob/add-set (ctob/make-token-set :name "core")))))
        "a single set"))

(t/deftest rename-scope-test
  (let [file (sample-file [] [])]
    (t/is (= :all (cfo/rename-scope (:data file) nil (thi/id :core))))
    (t/is (= #{(thi/id :dark)} (cfo/rename-scope (:data file) :set (thi/id :dark))))
    (t/is (= #{(thi/id :core) (thi/id :dark)} (cfo/rename-scope (:data file) :active (thi/id :dark)))
          "the active sets and the renamed one")))

(t/deftest count-token-references-test
  (let [file (-> (sample-file [["color.primary" :color "#FF0000"]
                               ["color.secondary" :color "{color.primary}"]
                               ["color.tertiary" :color "#00FF00"]]
                              [["color.secondary" :color "{color.primary}"]])
                 (tho/add-rect :rect-1)
                 (tht/apply-token-to-shape :rect-1 "color.primary" [:fill :stroke-color] [:fill] "#FF0000"))]
    (t/is (= 4 (clt/count-token-references (:data file) ["color.primary"] {:scope :all}))
          "two tokens and two attributes")
    (t/is (= 3 (clt/count-token-references (:data file) ["color.primary"] {:scope :active})))
    (t/is (= 0 (clt/count-token-references (:data file) ["color.tertiary"] {:scope :all})))))

