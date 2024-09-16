;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.tokens-lib-test
  (:require
   [app.common.data :as d]
   [app.common.fressian :as fres]
   [app.common.time :as dt]
   [app.common.transit :as tr]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(t/testing "token"
  (t/deftest make-token
    (let [now    (dt/now)
          token1 (ctob/make-token :name "test-token-1"
                                  :type :boolean
                                  :value true)
          token2 (ctob/make-token :name "test-token-2"
                                  :type :numeric
                                  :value 66
                                  :description "test description"
                                  :modified-at now)]

      (t/is (= (:name token1) "test-token-1"))
      (t/is (= (:type token1) :boolean))
      (t/is (= (:value token1) true))
      (t/is (nil? (:description token1)))
      (t/is (some? (:modified-at token1)))
      (t/is (ctob/valid-token? token1))

      (t/is (= (:name token2) "test-token-2"))
      (t/is (= (:type token2) :numeric))
      (t/is (= (:value token2) 66))
      (t/is (= (:description token2) "test description"))
      (t/is (= (:modified-at token2) now))
      (t/is (ctob/valid-token? token2))))

  (t/deftest invalid-tokens
    (let [args {:name 777
                :type :invalid}]
      (t/is (thrown-with-msg? Exception #"expected valid token"
                              (apply ctob/make-token args)))
      (t/is (false? (ctob/valid-token? {}))))))


(t/testing "token-set"
  (t/deftest make-token-set
    (let [now        (dt/now)
          token-set1 (ctob/make-token-set :name "test-token-set-1")
          token-set2 (ctob/make-token-set :name "test-token-set-2"
                                          :description "test description"
                                          :modified-at now
                                          :tokens [])]

      (t/is (= (:name token-set1) "test-token-set-1"))
      (t/is (nil? (:description token-set1)))
      (t/is (some? (:modified-at token-set1)))
      (t/is (empty? (:tokens token-set1)))

      (t/is (= (:name token-set2) "test-token-set-2"))
      (t/is (= (:description token-set2) "test description"))
      (t/is (= (:modified-at token-set2) now))
      (t/is (empty? (:tokens token-set2)))))

  (t/deftest invalid-token-set
    (let [args {:name 777
                :description 999}]
      (t/is (thrown-with-msg? Exception #"expected valid token set"
                              (apply ctob/make-token-set args))))))


(t/testing "token-theme"
  (t/deftest make-token-theme
    (let [now          (dt/now)
          token-theme1 (ctob/make-token-theme :name "test-token-theme-1")
          token-theme2 (ctob/make-token-theme :name "test-token-theme-2"
                                              :group "group-1"
                                              :description "test description"
                                              :is-source true
                                              :modified-at now
                                              :sets #{})]

      (t/is (= (:name token-theme1) "test-token-theme-1"))
      (t/is (= (:group token-theme1) ""))
      (t/is (nil? (:description token-theme1)))
      (t/is (false? (:is-source token-theme1)))
      (t/is (some? (:modified-at token-theme1)))
      (t/is (empty? (:sets token-theme1)))

      (t/is (= (:name token-theme2) "test-token-theme-2"))
      (t/is (= (:group token-theme2) "group-1"))
      (t/is (= (:description token-theme2) "test description"))
      (t/is (true? (:is-source token-theme2)))
      (t/is (= (:modified-at token-theme2) now))
      (t/is (empty? (:sets token-theme2)))))

  (t/deftest invalid-token-theme
    (let [args {:name 777
                :group nil
                :description 999
                :is-source 42}]
      (t/is (thrown-with-msg? Exception #"expected valid token theme"
                              (apply ctob/make-token-theme args))))))


(t/testing "tokens-lib"
  (t/deftest make-tokens-lib
    (let [tokens-lib (ctob/make-tokens-lib)]
      (t/is (= (ctob/set-count tokens-lib) 0))))

  (t/deftest invalid-tokens-lib
    (let [args {:sets nil
                :themes nil}]
      (t/is (thrown-with-msg? Exception #"expected valid tokens lib"
                              (apply ctob/make-tokens-lib args))))))


(t/testing "token-set in a lib"
  (t/deftest add-token-set
    (let [tokens-lib  (ctob/make-tokens-lib)
          token-set   (ctob/make-token-set :name "test-token-set")
          tokens-lib' (ctob/add-set tokens-lib token-set)

          token-sets' (ctob/get-sets tokens-lib')
          token-set'  (ctob/get-set tokens-lib' "test-token-set")]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (first token-sets') token-set))
      (t/is (= token-set' token-set))))

  (t/deftest add-token-set-with-group
    (let [tokens-lib  (ctob/make-tokens-lib)
          token-set   (ctob/make-token-set :name "test-group/test-token-set")
          tokens-lib' (ctob/add-set tokens-lib token-set)

          set-group   (ctob/get-set-group tokens-lib' "test-group")]

      (t/is (= (:attr1 set-group) "one"))
      (t/is (= (:attr2 set-group) "two"))))

  (t/deftest update-token-set
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-set "test-token-set"
                                           (fn [token-set]
                                             (assoc token-set
                                                    :description "some description")))
                          (ctob/update-set "not-existing-set"
                                           (fn [token-set]
                                             (assoc token-set
                                                    :description "no-effect"))))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "test-token-set")]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (:name token-set') "test-token-set"))
      (t/is (= (:description token-set') "some description"))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

  (t/deftest rename-token-set
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-set "test-token-set"
                                           (fn [token-set]
                                             (assoc token-set
                                                    :name "updated-name"))))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "updated-name")]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (:name token-set') "updated-name"))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

  (t/deftest delete-token-set
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set")))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-set "test-token-set")
                          (ctob/delete-set "not-existing-set"))

          token-set'  (ctob/get-set tokens-lib' "updated-name")]

      (t/is (= (ctob/set-count tokens-lib') 0))
      (t/is (nil? token-set')))))


(t/testing "token in a lib"
  (t/deftest add-token
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set")))
          token       (ctob/make-token :name "test-token"
                                       :type :boolean
                                       :value true)
          tokens-lib' (-> tokens-lib
                          (ctob/add-token-in-set "test-token-set" token)
                          (ctob/add-token-in-set "not-existing-set" token))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "test-token-set")
          token'      (get-in token-set' [:tokens "test-token"])]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (count (:tokens token-set')) 1))
      (t/is (= (:name token') "test-token"))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

  (t/deftest update-token
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-token-in-set "test-token-set"
                                                 (ctob/make-token :name "test-token-1"
                                                                  :type :boolean
                                                                  :value true))
                          (ctob/add-token-in-set "test-token-set"
                                                 (ctob/make-token :name "test-token-2"
                                                                  :type :boolean
                                                                  :value true)))

          tokens-lib' (-> tokens-lib
                          (ctob/update-token-in-set "test-token-set" "test-token-1"
                                                    (fn [token]
                                                      (assoc token
                                                             :description "some description"
                                                             :value false)))
                          (ctob/update-token-in-set "not-existing-set" "test-token-1"
                                                    (fn [token]
                                                      (assoc token
                                                             :name "no-effect")))
                          (ctob/update-token-in-set "test-token-set" "not-existing-token"
                                                    (fn [token]
                                                      (assoc token
                                                             :name "no-effect"))))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "test-token-set")
          token       (get-in token-set [:tokens "test-token-1"])
          token'      (get-in token-set' [:tokens "test-token-1"])]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (count (:tokens token-set')) 2))
      (t/is (= (d/index-of (keys (:tokens token-set')) "test-token-1") 0))
      (t/is (= (:name token') "test-token-1"))
      (t/is (= (:description token') "some description"))
      (t/is (= (:value token') false))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
      (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

  (t/deftest rename-token
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-token-in-set "test-token-set"
                                                 (ctob/make-token :name "test-token-1"
                                                                  :type :boolean
                                                                  :value true))
                          (ctob/add-token-in-set "test-token-set"
                                                 (ctob/make-token :name "test-token-2"
                                                                  :type :boolean
                                                                  :value true)))

          tokens-lib' (-> tokens-lib
                          (ctob/update-token-in-set "test-token-set" "test-token-1"
                                                    (fn [token]
                                                      (assoc token
                                                             :name "updated-name"))))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "test-token-set")
          token       (get-in token-set [:tokens "test-token-1"])
          token'      (get-in token-set' [:tokens "updated-name"])]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (count (:tokens token-set')) 2))
      (t/is (= (d/index-of (keys (:tokens token-set')) "updated-name") 0))
      (t/is (= (:name token') "updated-name"))
      (t/is (= (:description token') nil))
      (t/is (= (:value token') true))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
      (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

  (t/deftest delete-token
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-token-in-set "test-token-set"
                                                 (ctob/make-token :name "test-token"
                                                                  :type :boolean
                                                                  :value true)))
          tokens-lib' (-> tokens-lib
                          (ctob/delete-token-from-set "test-token-set" "test-token")
                          (ctob/delete-token-from-set "not-existing-set" "test-token")
                          (ctob/delete-token-from-set "test-set" "not-existing-token"))

          token-set   (ctob/get-set tokens-lib "test-token-set")
          token-set'  (ctob/get-set tokens-lib' "test-token-set")
          token'      (get-in token-set' [:tokens "test-token"])]

      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (count (:tokens token-set')) 0))
      (t/is (nil? token'))
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set))))))


(t/testing "token-theme in a lib"
  (t/deftest add-token-theme
    (let [tokens-lib  (ctob/make-tokens-lib)
          token-theme (ctob/make-token-theme :name "test-token-theme")
          tokens-lib' (ctob/add-theme tokens-lib token-theme)

          token-themes' (ctob/get-themes tokens-lib')
          token-theme'  (ctob/get-theme tokens-lib' "" "test-token-theme")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (first token-themes') token-theme))
      (t/is (= token-theme' token-theme))))

  (t/deftest update-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-theme "" "test-token-theme"
                                           (fn [token-theme]
                                             (assoc token-theme
                                                    :description "some description")))
                          (ctob/update-theme "" "not-existing-theme"
                                           (fn [token-theme]
                                             (assoc token-theme
                                                    :description "no-effect"))))

          token-theme   (ctob/get-theme tokens-lib "" "test-token-theme")
          token-theme'  (ctob/get-theme tokens-lib' "" "test-token-theme")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (:name token-theme') "test-token-theme"))
      (t/is (= (:description token-theme') "some description"))
      (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

  (t/deftest rename-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-theme "" "test-token-theme"
                                             (fn [token-theme]
                                               (assoc token-theme
                                                      :name "updated-name"))))

          token-theme   (ctob/get-theme tokens-lib "" "test-token-theme")
          token-theme'  (ctob/get-theme tokens-lib' "" "updated-name")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (:name token-theme') "updated-name"))
      (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

  (t/deftest delete-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-theme "" "test-token-theme")
                          (ctob/delete-theme "" "not-existing-theme"))

          token-theme'  (ctob/get-theme tokens-lib' "" "updated-name")]

      (t/is (= (ctob/theme-count tokens-lib') 0))
      (t/is (nil? token-theme'))))

  (t/deftest toggle-set-in-theme
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                             (ctob/add-set (ctob/make-token-set :name "token-set-2"))
                             (ctob/add-set (ctob/make-token-set :name "token-set-3"))
                             (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))
            tokens-lib'  (-> tokens-lib
                             (ctob/toggle-set-in-theme "" "test-token-theme" "token-set-1")
                             (ctob/toggle-set-in-theme "" "test-token-theme" "token-set-2")
                             (ctob/toggle-set-in-theme "" "test-token-theme" "token-set-2"))

            token-theme  (ctob/get-theme tokens-lib "" "test-token-theme")
            token-theme' (ctob/get-theme tokens-lib' "" "test-token-theme")]

        (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme))))))


(t/testing "serialization"
  (t/deftest transit-serialization
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-token-in-set "test-token-set" (ctob/make-token :name "test-token"
                                                                                   :type :boolean
                                                                                   :value true))
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme"))
                          (ctob/toggle-set-in-theme "" "test-token-theme" "test-token-set"))
          encoded-str (tr/encode-str tokens-lib)
          tokens-lib' (tr/decode-str encoded-str)]

      (t/is (ctob/valid-tokens-lib? tokens-lib'))
      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (ctob/theme-count tokens-lib') 1))))

  (t/deftest fressian-serialization
    (let [tokens-lib   (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                           (ctob/add-token-in-set "test-token-set" (ctob/make-token :name "test-token"
                                                                                    :type :boolean
                                                                                    :value true))
                           (ctob/add-theme (ctob/make-token-theme :name "test-token-theme"))
                           (ctob/toggle-set-in-theme "" "test-token-theme" "test-token-set"))
          encoded-blob (fres/encode tokens-lib)
          tokens-lib'  (fres/decode encoded-blob)]

      (t/is (ctob/valid-tokens-lib? tokens-lib'))
      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (ctob/theme-count tokens-lib') 1)))))

(t/testing "grouping"
  (t/deftest split-and-join
    (let [name "group.subgroup.name"
          path (ctob/split-path name ".")
          name' (ctob/join-path path ".")]
      (t/is (= (first path) "group"))
      (t/is (= (second path) "subgroup"))
      (t/is (= (nth path 2) "name"))
      (t/is (= name' name))))

  (t/deftest remove-spaces
    (let [name "group . subgroup . name"
          path (ctob/split-path name ".")]
      (t/is (= (first path) "group"))
      (t/is (= (second path) "subgroup"))
      (t/is (= (nth path 2) "name"))))

  (t/deftest group-and-ungroup
    (let [token1   (ctob/make-token :name "token1" :type :boolean :value true)
          token2   (ctob/make-token :name "some group.token2" :type :boolean :value true)

          token1'  (ctob/group-item token1 "big group" ".")
          token2'  (ctob/group-item token2 "big group" ".")
          token1'' (ctob/ungroup-item token1' ".")
          token2'' (ctob/ungroup-item token2' ".")]
      (t/is (= (:name token1') "big group.token1"))
      (t/is (= (:name token2') "big group.some group.token2"))
      (t/is (= (:name token1'') "token1"))
      (t/is (= (:name token2'') "some group.token2"))))

  (t/deftest get-groups-str
    (let [token1 (ctob/make-token :name "token1" :type :boolean :value true)
          token2 (ctob/make-token :name "some-group.token2" :type :boolean :value true)
          token3 (ctob/make-token :name "some-group.some-subgroup.token3" :type :boolean :value true)]
      (t/is (= (ctob/get-groups-str token1 ".") ""))
      (t/is (= (ctob/get-groups-str token2 ".") "some-group"))
      (t/is (= (ctob/get-groups-str token3 ".") "some-group.some-subgroup"))))

  (t/deftest get-final-name
    (let [token1 (ctob/make-token :name "token1" :type :boolean :value true)
          token2 (ctob/make-token :name "some-group.token2" :type :boolean :value true)
          token3 (ctob/make-token :name "some-group.some-subgroup.token3" :type :boolean :value true)]
      (t/is (= (ctob/get-final-name token1 ".") "token1"))
      (t/is (= (ctob/get-final-name token2 ".") "token2"))
      (t/is (= (ctob/get-final-name token3 ".") "token3"))))

  (t/testing "grouped tokens"
    (t/deftest grouped-tokens
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                           (ctob/add-token-in-set "test-token-set"
                                                  (ctob/make-token :name "token1"
                                                                   :type :boolean
                                                                   :value true))
                           (ctob/add-token-in-set "test-token-set"
                                                  (ctob/make-token :name "group1.token2"
                                                                   :type :boolean
                                                                   :value true))
                           (ctob/add-token-in-set "test-token-set"
                                                  (ctob/make-token :name "group1.token3"
                                                                   :type :boolean
                                                                   :value true))
                           (ctob/add-token-in-set "test-token-set"
                                                  (ctob/make-token :name "group1.subgroup11.token4"
                                                                   :type :boolean
                                                                   :value true))
                           (ctob/add-token-in-set "test-token-set"
                                                  (ctob/make-token :name "group2.token5"
                                                                   :type :boolean
                                                                   :value true)))

            set             (ctob/get-set tokens-lib "test-token-set")
            tokens-list     (ctob/get-tokens set)

            tokens-tree     (:tokens set)

            [node-token1 node-group1 node-group2]
            (ctob/get-children tokens-tree)

            [node-token2 node-token3 node-subgroup11]
            (ctob/get-children (second node-group1))

            [node-token4]
            (ctob/get-children (second node-subgroup11))

            [node-token5]
            (ctob/get-children (second node-group2))]

        (t/is (= (count tokens-list) 5))
        (t/is (= (:name (nth tokens-list 0)) "token1"))
        (t/is (= (:name (nth tokens-list 1)) "group1.token2"))
        (t/is (= (:name (nth tokens-list 2)) "group1.token3"))
        (t/is (= (:name (nth tokens-list 3)) "group1.subgroup11.token4"))
        (t/is (= (:name (nth tokens-list 4)) "group2.token5"))

        (t/is (= (first node-token1) "token1"))
        (t/is (= (ctob/group? (second node-token1)) false))
        (t/is (= (:name (second node-token1)) "token1"))

        (t/is (= (first node-group1) "group1"))
        (t/is (= (ctob/group? (second node-group1)) true))
        (t/is (= (count (second node-group1)) 3))

        (t/is (= (first node-token2) "token2"))
        (t/is (= (ctob/group? (second node-token2)) false))
        (t/is (= (:name (second node-token2)) "group1.token2"))

        (t/is (= (first node-token3) "token3"))
        (t/is (= (ctob/group? (second node-token3)) false))
        (t/is (= (:name (second node-token3)) "group1.token3"))

        (t/is (= (first node-subgroup11) "subgroup11"))
        (t/is (= (ctob/group? (second node-subgroup11)) true))
        (t/is (= (count (second node-subgroup11)) 1))

        (t/is (= (first node-token4) "token4"))
        (t/is (= (ctob/group? (second node-token4)) false))
        (t/is (= (:name (second node-token4)) "group1.subgroup11.token4"))

        (t/is (= (first node-token5) "token5"))
        (t/is (= (ctob/group? (second node-token5)) false))
        (t/is (= (:name (second node-token5)) "group2.token5"))))

    (t/deftest update-token-in-groups
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "test-token-1"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-2"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-3"
                                                                    :type :boolean
                                                                    :value true)))

            tokens-lib' (-> tokens-lib
                            (ctob/update-token-in-set "test-token-set" "group1.test-token-2"
                                                      (fn [token]
                                                        (assoc token
                                                               :description "some description"
                                                               :value false))))

            token-set   (ctob/get-set tokens-lib "test-token-set")
            token-set'  (ctob/get-set tokens-lib' "test-token-set")
            group1'     (get-in token-set' [:tokens "group1"])
            token       (get-in token-set [:tokens "group1" "test-token-2"])
            token'      (get-in token-set' [:tokens "group1" "test-token-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count group1') 2))
        (t/is (= (d/index-of (keys group1') "test-token-2") 0))
        (t/is (= (:name token') "group1.test-token-2"))
        (t/is (= (:description token') "some description"))
        (t/is (= (:value token') false))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/deftest rename-token-in-groups
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "test-token-1"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-2"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-3"
                                                                    :type :boolean
                                                                    :value true)))

            tokens-lib' (-> tokens-lib
                            (ctob/update-token-in-set "test-token-set" "group1.test-token-2"
                                                      (fn [token]
                                                        (assoc token
                                                               :name "group1.updated-name"))))

            token-set   (ctob/get-set tokens-lib "test-token-set")
            token-set'  (ctob/get-set tokens-lib' "test-token-set")
            group1'     (get-in token-set' [:tokens "group1"])
            token       (get-in token-set [:tokens "group1" "test-token-2"])
            token'      (get-in token-set' [:tokens "group1" "updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count group1') 2))
        (t/is (= (d/index-of (keys group1') "updated-name") 0))
        (t/is (= (:name token') "group1.updated-name"))
        (t/is (= (:description token') nil))
        (t/is (= (:value token') true))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/deftest move-token-of-group
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "test-token-1"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-2"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-3"
                                                                    :type :boolean
                                                                    :value true)))

            tokens-lib' (-> tokens-lib
                            (ctob/update-token-in-set "test-token-set" "group1.test-token-2"
                                                      (fn [token]
                                                        (assoc token
                                                               :name "group2.updated-name"))))

            token-set   (ctob/get-set tokens-lib "test-token-set")
            token-set'  (ctob/get-set tokens-lib' "test-token-set")
            group1'     (get-in token-set' [:tokens "group1"])
            group2'     (get-in token-set' [:tokens "group2"])
            token       (get-in token-set [:tokens "group1" "test-token-2"])
            token'      (get-in token-set' [:tokens "group2" "updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count group1') 1))
        (t/is (= (count group2') 1))
        (t/is (= (d/index-of (keys group2') "updated-name") 0))
        (t/is (= (:name token') "group2.updated-name"))
        (t/is (= (:description token') nil))
        (t/is (= (:value token') true))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/deftest delete-token-in-group
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "test-token-1"
                                                                    :type :boolean
                                                                    :value true))
                            (ctob/add-token-in-set "test-token-set"
                                                   (ctob/make-token :name "group1.test-token-2"
                                                                    :type :boolean
                                                                    :value true)))
            tokens-lib' (-> tokens-lib
                            (ctob/delete-token-from-set "test-token-set" "group1.test-token-2"))

            token-set   (ctob/get-set tokens-lib "test-token-set")
            token-set'  (ctob/get-set tokens-lib' "test-token-set")
            token'      (get-in token-set' [:tokens "group1" "test-token-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count (:tokens token-set')) 1))
        (t/is (nil? token'))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set))))))

  (t/testing "grouped sets"
    (t/deftest grouped-sets
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                           (ctob/add-set (ctob/make-token-set :name "group1/token-set-2"))
                           (ctob/add-set (ctob/make-token-set :name "group1/token-set-3"))
                           (ctob/add-set (ctob/make-token-set :name "group1/subgroup11/token-set-4"))
                           (ctob/add-set (ctob/make-token-set :name "group2/token-set-5")))

            sets-list     (ctob/get-sets tokens-lib)

            sets-tree     (ctob/get-set-tree tokens-lib)

            [node-set1 node-group1 node-group2]
            (ctob/get-children sets-tree)

            [node-set2 node-set3 node-subgroup11]
            (ctob/get-children (second node-group1))

            [node-set4]
            (ctob/get-children (second node-subgroup11))

            [node-set5]
            (ctob/get-children (second node-group2))]

        (t/is (= (count sets-list) 5))
        (t/is (= (:name (nth sets-list 0)) "token-set-1"))
        (t/is (= (:name (nth sets-list 1)) "group1/token-set-2"))
        (t/is (= (:name (nth sets-list 2)) "group1/token-set-3"))
        (t/is (= (:name (nth sets-list 3)) "group1/subgroup11/token-set-4"))
        (t/is (= (:name (nth sets-list 4)) "group2/token-set-5"))

        (t/is (= (first node-set1) "token-set-1"))
        (t/is (= (ctob/group? (second node-set1)) false))
        (t/is (= (:name (second node-set1)) "token-set-1"))

        (t/is (= (first node-group1) "group1"))
        (t/is (= (ctob/group? (second node-group1)) true))
        (t/is (= (count (second node-group1)) 3))

        (t/is (= (first node-set2) "token-set-2"))
        (t/is (= (ctob/group? (second node-set2)) false))
        (t/is (= (:name (second node-set2)) "group1/token-set-2"))

        (t/is (= (first node-set3) "token-set-3"))
        (t/is (= (ctob/group? (second node-set3)) false))
        (t/is (= (:name (second node-set3)) "group1/token-set-3"))

        (t/is (= (first node-subgroup11) "subgroup11"))
        (t/is (= (ctob/group? (second node-subgroup11)) true))
        (t/is (= (count (second node-subgroup11)) 1))

        (t/is (= (first node-set4) "token-set-4"))
        (t/is (= (ctob/group? (second node-set4)) false))
        (t/is (= (:name (second node-set4)) "group1/subgroup11/token-set-4"))

        (t/is (= (first node-set5) "token-set-5"))
        (t/is (= (ctob/group? (second node-set5)) false))
        (t/is (= (:name (second node-set5)) "group2/token-set-5"))))

    (t/deftest update-set-in-groups
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-2"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-3"))
                            (ctob/add-set (ctob/make-token-set :name "group1/subgroup11/token-set-4"))
                            (ctob/add-set (ctob/make-token-set :name "group2/token-set-5")))

            tokens-lib' (-> tokens-lib
                            (ctob/update-set "group1/token-set-2"
                                                      (fn [token-set]
                                                        (assoc token-set :description "some description"))))

            sets-tree   (ctob/get-set-tree tokens-lib)
            sets-tree'  (ctob/get-set-tree tokens-lib')
            group1'     (get sets-tree' "group1")
            token-set   (get-in sets-tree ["group1" "token-set-2"])
            token-set'  (get-in sets-tree' ["group1" "token-set-2"])]

        (t/is (= (ctob/set-count tokens-lib') 5))
        (t/is (= (count group1') 3))
        (t/is (= (d/index-of (keys group1') "token-set-2") 0))
        (t/is (= (:name token-set') "group1/token-set-2"))
        (t/is (= (:description token-set') "some description"))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

    (t/deftest rename-set-in-groups
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-2"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-3"))
                            (ctob/add-set (ctob/make-token-set :name "group1/subgroup11/token-set-4"))
                            (ctob/add-set (ctob/make-token-set :name "group2/token-set-5")))

            tokens-lib' (-> tokens-lib
                            (ctob/update-set "group1/token-set-2"
                                                      (fn [token-set]
                                                        (assoc token-set
                                                               :name "group1/updated-name"))))

            sets-tree   (ctob/get-set-tree tokens-lib)
            sets-tree'  (ctob/get-set-tree tokens-lib')
            group1'     (get sets-tree' "group1")
            token-set   (get-in sets-tree ["group1" "token-set-2"])
            token-set'  (get-in sets-tree' ["group1" "updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 5))
        (t/is (= (count group1') 3))
        (t/is (= (d/index-of (keys group1') "updated-name") 0))
        (t/is (= (:name token-set') "group1/updated-name"))
        (t/is (= (:description token-set') nil))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

    (t/deftest move-set-of-group
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-2"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-3"))
                            (ctob/add-set (ctob/make-token-set :name "group1/subgroup11/token-set-4"))
                            #_(ctob/add-set (ctob/make-token-set :name "group2/token-set-5")))

            tokens-lib' (-> tokens-lib
                            (ctob/update-set "group1/token-set-2"
                                                      (fn [token-set]
                                                        (assoc token-set
                                                               :name "group2/updated-name"))))

            sets-tree   (ctob/get-set-tree tokens-lib)
            sets-tree'  (ctob/get-set-tree tokens-lib')
            group1'     (get sets-tree' "group1")
            group2'     (get sets-tree' "group2")
            token-set   (get-in sets-tree ["group1" "token-set-2"])
            token-set'  (get-in sets-tree' ["group2" "updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 4))
        (t/is (= (count group1') 2))
        (t/is (= (count group2') 1))
        (t/is (= (d/index-of (keys group2') "updated-name") 0))
        (t/is (= (:name token-set') "group2/updated-name"))
        (t/is (= (:description token-set') nil))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

    (t/deftest delete-set-in-group
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-2")))

            tokens-lib' (-> tokens-lib
                            (ctob/delete-set  "group1/token-set-2"))

            sets-tree'  (ctob/get-set-tree tokens-lib')
            token-set'  (get-in sets-tree' ["group1" "token-set-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count sets-tree') 1))
        (t/is (nil? token-set')))))

  (t/testing "grouped themes"
    (t/deftest grouped-themes
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                           (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2"))
                           (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-3"))
                           (ctob/add-theme (ctob/make-token-theme :group "group2" :name "token-theme-4")))

            themes-list   (ctob/get-themes tokens-lib)

            themes-tree   (ctob/get-theme-tree tokens-lib)

            [node-group0 node-group1 node-group2]
            (ctob/get-children themes-tree)

            [node-theme1]
            (ctob/get-children (second node-group0))

            [node-theme2 node-theme3]
            (ctob/get-children (second node-group1))

            [node-theme4]
            (ctob/get-children (second node-group2))]

        (t/is (= (count themes-list) 4))
        (t/is (= (:name (nth themes-list 0)) "token-theme-1"))
        (t/is (= (:name (nth themes-list 1)) "token-theme-2"))
        (t/is (= (:name (nth themes-list 2)) "token-theme-3"))
        (t/is (= (:name (nth themes-list 3)) "token-theme-4"))
        (t/is (= (:group (nth themes-list 0)) ""))
        (t/is (= (:group (nth themes-list 1)) "group1"))
        (t/is (= (:group (nth themes-list 2)) "group1"))
        (t/is (= (:group (nth themes-list 3)) "group2"))

        (t/is (= (first node-group0) ""))
        (t/is (= (ctob/group? (second node-group0)) true))
        (t/is (= (count (second node-group0)) 1))

        (t/is (= (first node-theme1) "token-theme-1"))
        (t/is (= (ctob/group? (second node-theme1)) false))
        (t/is (= (:name (second node-theme1)) "token-theme-1"))

        (t/is (= (first node-group1) "group1"))
        (t/is (= (ctob/group? (second node-group1)) true))
        (t/is (= (count (second node-group1)) 2))

        (t/is (= (first node-theme2) "token-theme-2"))
        (t/is (= (ctob/group? (second node-theme2)) false))
        (t/is (= (:name (second node-theme2)) "token-theme-2"))

        (t/is (= (first node-theme3) "token-theme-3"))
        (t/is (= (ctob/group? (second node-theme3)) false))
        (t/is (= (:name (second node-theme3)) "token-theme-3"))

        (t/is (= (first node-theme4) "token-theme-4"))
        (t/is (= (ctob/group? (second node-theme4)) false))
        (t/is (= (:name (second node-theme4)) "token-theme-4"))))

    (t/deftest update-theme-in-groups
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-3"))
                             (ctob/add-theme (ctob/make-token-theme :group "group2" :name "token-theme-4")))

            tokens-lib'  (-> tokens-lib
                             (ctob/update-theme "group1" "token-theme-2"
                                                       (fn [token-theme]
                                                         (assoc token-theme :description "some description"))))

            themes-tree  (ctob/get-theme-tree tokens-lib)
            themes-tree' (ctob/get-theme-tree tokens-lib')
            group1'      (get themes-tree' "group1")
            token-theme  (get-in themes-tree ["group1" "token-theme-2"])
            token-theme' (get-in themes-tree' ["group1" "token-theme-2"])]

        (t/is (= (ctob/theme-count tokens-lib') 4))
        (t/is (= (count group1') 2))
        (t/is (= (d/index-of (keys group1') "token-theme-2") 0))
        (t/is (= (:name token-theme') "token-theme-2"))
        (t/is (= (:group token-theme') "group1"))
        (t/is (= (:description token-theme') "some description"))
        (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

    (t/deftest rename-theme-in-groups
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-3"))
                             (ctob/add-theme (ctob/make-token-theme :group "group2" :name "token-theme-4")))

            tokens-lib'  (-> tokens-lib
                             (ctob/update-theme "group1" "token-theme-2"
                                                       (fn [token-theme]
                                                         (assoc token-theme
                                                                :name "updated-name"))))

            themes-tree  (ctob/get-theme-tree tokens-lib)
            themes-tree' (ctob/get-theme-tree tokens-lib')
            group1'      (get themes-tree' "group1")
            token-theme  (get-in themes-tree ["group1" "token-theme-2"])
            token-theme' (get-in themes-tree' ["group1" "updated-name"])]

        (t/is (= (ctob/theme-count tokens-lib') 4))
        (t/is (= (count group1') 2))
        (t/is (= (d/index-of (keys group1') "updated-name") 0))
        (t/is (= (:name token-theme') "updated-name"))
        (t/is (= (:group token-theme') "group1"))
        (t/is (= (:description token-theme') nil))
        (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

    (t/deftest move-theme-of-group
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-3"))
                             #_(ctob/add-theme (ctob/make-token-theme :group "group2" :name "token-theme-4")))

            tokens-lib'  (-> tokens-lib
                             (ctob/update-theme "group1" "token-theme-2"
                                                       (fn [token-theme]
                                                         (assoc token-theme
                                                                :name "updated-name"
                                                                :group "group2"))))

            themes-tree  (ctob/get-theme-tree tokens-lib)
            themes-tree' (ctob/get-theme-tree tokens-lib')
            group1'      (get themes-tree' "group1")
            group2'      (get themes-tree' "group2")
            token-theme  (get-in themes-tree ["group1" "token-theme-2"])
            token-theme' (get-in themes-tree' ["group2" "updated-name"])]

        (t/is (= (ctob/theme-count tokens-lib') 3))
        (t/is (= (count group1') 1))
        (t/is (= (count group2') 1))
        (t/is (= (d/index-of (keys group2') "updated-name") 0))
        (t/is (= (:name token-theme') "updated-name"))
        (t/is (= (:group token-theme') "group2"))
        (t/is (= (:description token-theme') nil))
        (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

    (t/deftest delete-theme-in-group
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                             (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2")))

            tokens-lib'  (-> tokens-lib
                             (ctob/delete-theme "group1" "token-theme-2"))

            themes-tree' (ctob/get-theme-tree tokens-lib')
            token-theme' (get-in themes-tree' ["group1" "token-theme-2"])]

        (t/is (= (ctob/theme-count tokens-lib') 1))
        (t/is (= (count themes-tree') 1))
        (t/is (nil? token-theme'))))))
