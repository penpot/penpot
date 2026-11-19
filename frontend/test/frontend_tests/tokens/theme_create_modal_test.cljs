;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.theme-create-modal-test
  "The sets list of the theme editor: which sets it shows as ticked, and
  what ticking a set or a group does to the theme."
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.ui.workspace.tokens.themes.create-modal :as create-modal]
   [cljs.test :as t :include-macros true]))

(def ^:private toggle-set @#'create-modal/toggle-set)
(def ^:private toggle-set-group @#'create-modal/toggle-set-group)
(def ^:private make-lib-with-theme @#'create-modal/make-lib-with-theme)
(def ^:private make-status-with-theme @#'create-modal/make-status-with-theme)

(defn- sample-lib
  "A library with the set `light` and the group `brand` holding the sets
  `a` and `b` and the subgroup `x` with the set `c`."
  []
  (-> (ctob/make-tokens-lib)
      (ctob/add-set (ctob/make-token-set :name "light"))
      (ctob/add-set (ctob/make-token-set :name "brand/a"))
      (ctob/add-set (ctob/make-token-set :name "brand/b"))
      (ctob/add-set (ctob/make-token-set :name "brand/x/c"))))

(defn- sample-theme
  [& set-names]
  (ctob/make-token-theme :name "Light" :sets (set set-names)))

(defn- set-id
  [lib set-name]
  (ctob/get-id (ctob/get-set-by-name lib set-name)))

(t/deftest toggle-set-ticks-and-unticks-by-id
  (let [lib    (sample-lib)
        id     (set-id lib "light")
        ticked (toggle-set (sample-theme) lib id)]
    (t/is (= #{"light"} (:sets ticked)))
    (t/is (= #{} (:sets (toggle-set ticked lib id))))))

(t/deftest toggle-set-keeps-the-other-sets
  (let [lib (sample-lib)]
    (t/is (= #{"brand/a" "light"}
             (:sets (toggle-set (sample-theme "brand/a") lib (set-id lib "light")))))))

(t/deftest toggle-set-group-ticks-all-its-sets-when-none-is-ticked
  (let [sets (ctob/get-sets (sample-lib))]
    (t/is (= #{"light" "brand/a" "brand/b" "brand/x/c"}
             (:sets (toggle-set-group (sample-theme "light") sets ["brand"]))))))

(t/deftest toggle-set-group-unticks-all-its-sets-when-some-are-ticked
  (let [sets (ctob/get-sets (sample-lib))]
    (t/is (= #{"light"}
             (:sets (toggle-set-group (sample-theme "light" "brand/a") sets ["brand"]))))))

(t/deftest toggle-set-group-unticks-all-its-sets-when-only-a-nested-one-is-ticked
  (let [sets (ctob/get-sets (sample-lib))]
    (t/is (= #{"light"}
             (:sets (toggle-set-group (sample-theme "light" "brand/x/c") sets ["brand"]))))))

(t/deftest toggle-set-group-unticks-all-its-sets-when-all-are-ticked
  (let [sets (ctob/get-sets (sample-lib))]
    (t/is (= #{}
             (:sets (toggle-set-group (sample-theme "brand/a" "brand/b" "brand/x/c") sets ["brand"]))))))

(t/deftest the-editor-shows-the-sets-of-the-theme-as-ticked
  (let [lib    (sample-lib)
        theme  (sample-theme "brand/a")
        lib'   (make-lib-with-theme theme (ctob/get-sets lib))
        status (make-status-with-theme lib' theme)]
    (t/is (ctos/set-active? status (set-id lib "brand/a")))
    (t/is (not (ctos/set-active? status (set-id lib "light"))))
    (t/is (= :partial (cfo/sets-at-path-all-active? status lib' ["brand"])))))
