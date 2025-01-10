;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.tokens-lib-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.data :as d]
   [app.common.time :as dt]
   [app.common.transit :as tr]
   [app.common.types.tokens-lib :as ctob]
   [clojure.test :as t]))

(defn setup-virtual-time
  [next]
  (let [current (volatile! (inst-ms (dt/now)))]
    (with-redefs [dt/now #(dt/parse-instant (vswap! current inc))]
      (next))))

(t/use-fixtures :once setup-virtual-time)

(t/deftest tokens
  (t/testing "make-token"
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

  (t/testing "invalid-tokens"
    (let [args {:name 777
                :type :invalid}]
      (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"expected valid token"
                              (apply ctob/make-token args)))
      (t/is (false? (ctob/valid-token? {})))))

  (t/testing "find-token-value-references"
    (t/testing "finds references inside curly braces in a string"
      (t/is (= #{"foo" "bar"} (ctob/find-token-value-references "{foo} + {bar}")))
      (t/testing "ignores extra text"
        (t/is (= #{"foo.bar.baz"} (ctob/find-token-value-references "{foo.bar.baz} + something")))))
    (t/testing "ignores string without references"
      (t/is (nil? (ctob/find-token-value-references "1 + 2"))))
    (t/testing "handles edge-case for extra curly braces"
      (t/is (= #{"foo" "bar"} (ctob/find-token-value-references "{foo}} + {bar}"))))))

(t/deftest token-set
  (t/testing "make-token-set"
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

  (t/testing "invalid-token-set"
    (let [args {:name 777
                :description 999}]
      (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"expected valid token set"
                              (apply ctob/make-token-set args)))))

  (t/testing "move-token-set"
    (t/testing "flat"
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "A"))
                           (ctob/add-set (ctob/make-token-set :name "B"))
                           (ctob/add-set (ctob/make-token-set :name "Move")))
            move (fn [from-path to-path before-path before-group?]
                   (->> (ctob/move-set tokens-lib from-path to-path before-path before-group?)
                        (ctob/get-ordered-set-names)
                        (into [])))]
        (t/testing "move to top"
          (t/is (= ["Move" "A" "B"] (move ["Move"] ["Move"] ["A"] false))))

        (t/testing "move in-between"
          (t/is (= ["A" "Move" "B"] (move ["Move"] ["Move"] ["B"] false))))

        (t/testing "move to bottom"
          (t/is (= ["A" "B" "Move"] (move ["Move"] ["Move"] nil false))))))

    (t/testing "nested"
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "Foo/Baz"))
                           (ctob/add-set (ctob/make-token-set :name "Foo/Bar"))
                           (ctob/add-set (ctob/make-token-set :name "Foo")))
            move (fn [from-path to-path before-path before-group?]
                   (->> (ctob/move-set tokens-lib from-path to-path before-path before-group?)
                        (ctob/get-ordered-set-names)
                        (into [])))]
        (t/testing "move outside of group"
          (t/is (= ["Foo/Baz" "Bar" "Foo"] (move ["Foo" "Bar"] ["Bar"] ["Foo"] false)))
          (t/is (= ["Bar" "Foo/Baz" "Foo"] (move ["Foo" "Bar"] ["Bar"] ["Foo" "Baz"] true)))
          (t/is (= ["Foo/Baz" "Foo" "Bar"] (move ["Foo" "Bar"] ["Bar"] nil false))))

        (t/testing "move inside of group"
          (t/is (= ["Foo/Foo" "Foo/Baz" "Foo/Bar"] (move ["Foo"] ["Foo" "Foo"] ["Foo" "Baz"] false)))
          (t/is (= ["Foo/Baz" "Foo/Bar" "Foo/Foo"] (move ["Foo"] ["Foo" "Foo"] nil false))))))

    (t/testing "updates theme set names"
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "Foo/Bar/Baz"))
                           (ctob/add-set (ctob/make-token-set :name "Other"))
                           (ctob/add-theme (ctob/make-token-theme :name "Theme"
                                                                  :sets #{"Foo/Bar/Baz"}))
                           (ctob/move-set ["Foo" "Bar" "Baz"] ["Other/Baz"] nil nil))]
        (t/is (= #{"Other/Baz"} (:sets (ctob/get-theme tokens-lib "" "Theme")))))))

  (t/testing "move-token-set-group"
    (t/testing "reordering"
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "Foo/A"))
                           (ctob/add-set (ctob/make-token-set :name "Foo/B"))
                           (ctob/add-set (ctob/make-token-set :name "Bar/Foo"))
                           (ctob/add-theme (ctob/make-token-theme :name "Theme"
                                                                  :sets #{"Foo/A" "Bar/Foo"})))
            move (fn [from-path to-path before-path before-group?]
                   (->> (ctob/move-set-group tokens-lib from-path to-path before-path before-group?)
                        (ctob/get-ordered-set-names)
                        (into [])))]
        (t/is (= ["Bar/Foo" "Bar/Foo/A" "Bar/Foo/B"] (move ["Foo"] ["Bar" "Foo"] nil nil)))
        (t/is (= ["Bar/Foo" "Foo/A" "Foo/B"] (move ["Bar"] ["Bar"] ["Foo"] true)))))

    (t/testing "updates theme set names"
      (let [tokens-lib (-> (ctob/make-tokens-lib)
                           (ctob/add-set (ctob/make-token-set :name "Foo/A"))
                           (ctob/add-set (ctob/make-token-set :name "Foo/B"))
                           (ctob/add-set (ctob/make-token-set :name "Bar/Foo"))
                           (ctob/add-theme (ctob/make-token-theme :name "Theme"
                                                                  :sets #{"Foo/A" "Bar/Foo"}))
                           (ctob/move-set-group ["Foo"] ["Bar" "Foo"] nil nil))]
        (t/is (= #{"Bar/Foo/A" "Bar/Foo"} (:sets (ctob/get-theme tokens-lib "" "Theme")))))))

  (t/testing "tokens-tree"
    (let [tokens-lib (-> (ctob/make-tokens-lib)
                         (ctob/add-set (ctob/make-token-set :name "A"
                                                            :tokens {"foo.bar.baz" (ctob/make-token :name "foo.bar.baz"
                                                                                                    :type :boolean
                                                                                                    :value true)
                                                                     "foo.bar.bam" (ctob/make-token :name "foo.bar.bam"
                                                                                                    :type :boolean
                                                                                                    :value true)
                                                                     "baz.boo" (ctob/make-token :name "baz.boo"
                                                                                                :type :boolean
                                                                                                :value true)})))
          expected (-> (ctob/get-set tokens-lib "A")
                       (ctob/get-tokens-tree))]
      (t/is (= (get-in expected ["foo" "bar" "baz" :name]) "foo.bar.baz"))
      (t/is (= (get-in expected ["foo" "bar" "bam" :name]) "foo.bar.bam"))
      (t/is (= (get-in expected ["baz" "boo" :name]) "baz.boo")))))

(t/deftest token-theme
  (t/testing "make-token-theme"
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

  (t/testing "invalid-token-theme"
    (let [args {:name 777
                :group nil
                :description 999
                :is-source 42}]
      (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"expected valid token theme"
                              (apply ctob/make-token-theme args))))))


(t/deftest tokens-lib
  (t/testing "make-tokens-lib"
    (let [tokens-lib (ctob/make-tokens-lib)]
      (t/is (= (ctob/set-count tokens-lib) 0))))

  (t/testing "invalid-tokens-lib"
    (let [args {:sets nil
                :themes nil}]
      (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception) #"expected valid tokens lib"
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

  (t/deftest rename-token-set-group
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                          (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child-1"))
                          (ctob/add-set (ctob/make-token-set :name "foo/bar/baz/baz-child-2"))
                          (ctob/add-theme (ctob/make-token-theme :name "theme" :sets #{"foo/bar/baz/baz-child-1"})))
          tokens-lib' (-> tokens-lib
                          (ctob/rename-set-group ["foo" "bar"] "bar-renamed")
                          (ctob/rename-set-group ["foo" "bar-renamed" "baz"] "baz-renamed"))
          expected-set-names (ctob/get-ordered-set-names tokens-lib')
          expected-theme-sets (-> (ctob/get-theme tokens-lib' "" "theme")
                                  :sets)]
      (t/is (= expected-set-names
               '("foo/bar-renamed/baz"
                 "foo/bar-renamed/baz-renamed/baz-child-1"
                 "foo/bar-renamed/baz-renamed/baz-child-2")))
      (t/is (= expected-theme-sets #{"foo/bar-renamed/baz-renamed/baz-child-1"}))))

  (t/deftest delete-token-set
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme" :sets #{"test-token-set"})))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-set-path "S-test-token-set")
                          (ctob/delete-set-path "S-not-existing-set"))

          token-set'  (ctob/get-set tokens-lib' "updated-name")
          token-theme'  (ctob/get-theme tokens-lib' "" "test-token-theme")]

      (t/is (= (ctob/set-count tokens-lib') 0))
      (t/is (= (:sets token-theme') #{}))
      (t/is (nil? token-set'))))

  (t/deftest active-themes-set-names
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set")))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-set-path "S-test-token-set")
                          (ctob/delete-set-path "S-not-existing-set"))

          token-set'  (ctob/get-set tokens-lib' "updated-name")]

      (t/is (= (ctob/set-count tokens-lib') 0))
      (t/is (nil? token-set')))))


(t/deftest token-in-a-lib
  (t/testing "add-token"
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

  (t/testing "update-token"
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

  (t/testing "rename-token"
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

  (t/testing "delete-token"
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
      (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

  (t/testing "list-active-themes-tokens-in-order"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "out-of-order-theme"
                                                                 ;; Out of order sets in theme
                                                                 :sets ["unknown-set" "set-b" "set-a"]))
                          (ctob/set-active-themes #{"/out-of-order-theme"})

                          (ctob/add-set (ctob/make-token-set :name "set-a"))
                          (ctob/add-token-in-set "set-a" (ctob/make-token :name "set-a-token"
                                                                          :type :boolean
                                                                          :value true))
                          (ctob/add-set (ctob/make-token-set :name "set-b"))
                          (ctob/add-token-in-set "set-b" (ctob/make-token :name "set-b-token"
                                                                          :type :boolean
                                                                          :value true))
                          ;; Ignore this set
                          (ctob/add-set (ctob/make-token-set :name "inactive-set"))
                          (ctob/add-token-in-set "inactive-set" (ctob/make-token :name "inactive-set-token"
                                                                                 :type :boolean
                                                                                 :value true)))

          expected-order (ctob/get-ordered-set-names tokens-lib)
          expected-tokens (ctob/get-active-themes-set-tokens tokens-lib)
          expected-token-names (mapv key expected-tokens)]
      (t/is (= '("set-a" "set-b" "inactive-set") expected-order))
      (t/is (= ["set-a-token" "set-b-token"] expected-token-names))))

  (t/testing "sets-at-path-active-state"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)

                          (ctob/add-set (ctob/make-token-set :name "foo/bar/baz"))
                          (ctob/add-set (ctob/make-token-set :name "foo/bar/bam"))

                          (ctob/add-theme (ctob/make-token-theme :name "none"))
                          (ctob/add-theme (ctob/make-token-theme :name "partial"
                                                                 :sets #{"foo/bar/baz"}))
                          (ctob/add-theme (ctob/make-token-theme :name "all"
                                                                 :sets #{"foo/bar/baz"
                                                                         "foo/bar/bam"}))
                          (ctob/add-theme (ctob/make-token-theme :name "invalid"
                                                                 :sets #{"foo/missing"})))

          expected-none (-> tokens-lib
                            (ctob/set-active-themes #{"/none"})
                            (ctob/sets-at-path-all-active? ["foo"]))
          expected-all (-> tokens-lib
                           (ctob/set-active-themes #{"/all"})
                           (ctob/sets-at-path-all-active? ["foo"]))
          expected-partial (-> tokens-lib
                               (ctob/set-active-themes #{"/partial"})
                               (ctob/sets-at-path-all-active? ["foo"]))
          expected-invalid-none (-> tokens-lib
                                    (ctob/set-active-themes #{"/invalid"})
                                    (ctob/sets-at-path-all-active? ["foo"]))]
      (t/is (= :none expected-none))
      (t/is (= :all expected-all))
      (t/is (= :partial expected-partial))
      (t/is (= :none expected-invalid-none)))))

(t/deftest token-theme-in-a-lib
  (t/testing "add-token-theme"
    (let [tokens-lib  (ctob/make-tokens-lib)
          token-theme (ctob/make-token-theme :name "test-token-theme")
          tokens-lib' (ctob/add-theme tokens-lib token-theme)

          token-themes' (ctob/get-themes tokens-lib')
          token-theme'  (ctob/get-theme tokens-lib' "" "test-token-theme")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (first token-themes') token-theme))
      (t/is (= token-theme' token-theme))))

  (t/testing "update-token-theme"
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

  (t/testing "rename-token-theme"
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

  (t/testing "delete-token-theme"
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-theme "" "test-token-theme")
                          (ctob/delete-theme "" "not-existing-theme"))

          token-theme'  (ctob/get-theme tokens-lib' "" "updated-name")]

      (t/is (= (ctob/theme-count tokens-lib') 0))
      (t/is (nil? token-theme'))))

  (t/testing "toggle-set-in-theme"
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


(t/deftest serialization
  (t/testing "transit-serialization"
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

  #?(:clj
     (t/testing "fressian-serialization"
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
         (t/is (= (ctob/theme-count tokens-lib') 1))))))

(t/deftest grouping
  (t/testing "split-and-join"
    (let [name "group/subgroup/name"
          path (ctob/split-path name "/")
          name' (ctob/join-path path "/")]
      (t/is (= (first path) "group"))
      (t/is (= (second path) "subgroup"))
      (t/is (= (nth path 2) "name"))
      (t/is (= name' name))))

  (t/testing "remove-spaces"
    (let [name "group / subgroup / name"
          path (ctob/split-path name "/")]
      (t/is (= (first path) "group"))
      (t/is (= (second path) "subgroup"))
      (t/is (= (nth path 2) "name"))))

  (t/testing "group-and-ungroup"
    (let [token-set1   (ctob/make-token-set :name "token-set1")
          token-set2   (ctob/make-token-set :name "some group/token-set2")

          token-set1'  (ctob/group-item token-set1 "big group" "/")
          token-set2'  (ctob/group-item token-set2 "big group" "/")
          token-set1'' (ctob/ungroup-item token-set1' "/")
          token-set2'' (ctob/ungroup-item token-set2' "/")]
      (t/is (= (:name token-set1') "big group/token-set1"))
      (t/is (= (:name token-set2') "big group/some group/token-set2"))
      (t/is (= (:name token-set1'') "token-set1"))
      (t/is (= (:name token-set2'') "some group/token-set2"))))

  (t/testing "get-groups-str"
    (let [token-set1 (ctob/make-token-set :name "token-set1")
          token-set2 (ctob/make-token-set :name "some-group/token-set2")
          token-set3 (ctob/make-token-set :name "some-group/some-subgroup/token-set3")]
      (t/is (= (ctob/get-groups-str token-set1 "/") ""))
      (t/is (= (ctob/get-groups-str token-set2 "/") "some-group"))
      (t/is (= (ctob/get-groups-str token-set3 "/") "some-group/some-subgroup"))))

  (t/testing "get-final-name"
    (let [token-set1 (ctob/make-token-set :name "token-set1")
          token-set2 (ctob/make-token-set :name "some-group/token-set2")
          token-set3 (ctob/make-token-set :name "some-group/some-subgroup/token-set3")]
      (t/is (= (ctob/get-final-name token-set1 "/") "token-set1"))
      (t/is (= (ctob/get-final-name token-set2 "/") "token-set2"))
      (t/is (= (ctob/get-final-name token-set3 "/") "token-set3"))))

  (t/testing "grouped tokens"
    (t/testing "grouped-tokens"
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
            tokens-list     (vals (:tokens set))]

        (t/is (= (count tokens-list) 5))
        (t/is (= (:name (nth tokens-list 0)) "token1"))
        (t/is (= (:name (nth tokens-list 1)) "group1.token2"))
        (t/is (= (:name (nth tokens-list 2)) "group1.token3"))
        (t/is (= (:name (nth tokens-list 3)) "group1.subgroup11.token4"))
        (t/is (= (:name (nth tokens-list 4)) "group2.token5"))))

    (t/testing "update-token-in-groups"
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
            token       (get-in token-set [:tokens "group1.test-token-2"])
            token'      (get-in token-set' [:tokens "group1.test-token-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (:name token') "group1.test-token-2"))
        (t/is (= (:description token') "some description"))
        (t/is (= (:value token') false))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/testing "rename-token-in-groups"
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
            token       (get-in token-set [:tokens "group1.test-token-2"])
            token'      (get-in token-set' [:tokens "group1.updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (:name token') "group1.updated-name"))
        (t/is (= (:description token') nil))
        (t/is (= (:value token') true))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/testing "move-token-of-group"
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
            token       (get-in token-set [:tokens "group1.test-token-2"])
            token'      (get-in token-set' [:tokens "group2.updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (d/index-of (keys (:tokens token-set')) "group2.updated-name") 1))
        (t/is (= (:name token') "group2.updated-name"))
        (t/is (= (:description token') nil))
        (t/is (= (:value token') true))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        (t/is (dt/is-after? (:modified-at token') (:modified-at token)))))

    (t/testing "delete-token-in-group"
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
            token'      (get-in token-set' [:tokens "group1.test-token-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count (:tokens token-set')) 1))
        (t/is (nil? token'))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set))))))

  (t/testing "grouped sets"
    (t/testing "grouped-sets"
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

        (t/is (= (first node-set1) "S-token-set-1"))
        (t/is (= (ctob/group? (second node-set1)) false))
        (t/is (= (:name (second node-set1)) "token-set-1"))

        (t/is (= (first node-group1) "G-group1"))
        (t/is (= (ctob/group? (second node-group1)) true))
        (t/is (= (count (second node-group1)) 3))

        (t/is (= (first node-set2) "S-token-set-2"))
        (t/is (= (ctob/group? (second node-set2)) false))
        (t/is (= (:name (second node-set2)) "group1/token-set-2"))

        (t/is (= (first node-set3) "S-token-set-3"))
        (t/is (= (ctob/group? (second node-set3)) false))
        (t/is (= (:name (second node-set3)) "group1/token-set-3"))

        (t/is (= (first node-subgroup11) "G-subgroup11"))
        (t/is (= (ctob/group? (second node-subgroup11)) true))
        (t/is (= (count (second node-subgroup11)) 1))

        (t/is (= (first node-set4) "S-token-set-4"))
        (t/is (= (ctob/group? (second node-set4)) false))
        (t/is (= (:name (second node-set4)) "group1/subgroup11/token-set-4"))

        (t/is (= (first node-set5) "S-token-set-5"))
        (t/is (= (ctob/group? (second node-set5)) false))
        (t/is (= (:name (second node-set5)) "group2/token-set-5"))))

    (t/testing "update-set-in-groups"
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
            group1'     (get sets-tree' "G-group1")
            token-set   (get-in sets-tree ["G-group1" "S-token-set-2"])
            token-set'  (get-in sets-tree' ["G-group1" "S-token-set-2"])]

        (t/is (= (ctob/set-count tokens-lib') 5))
        (t/is (= (count group1') 3))
        (t/is (= (d/index-of (keys group1') "S-token-set-2") 0))
        (t/is (= (:name token-set') "group1/token-set-2"))
        (t/is (= (:description token-set') "some description"))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

    (t/testing "rename-set-in-groups"
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
            group1'     (get sets-tree' "G-group1")
            token-set   (get-in sets-tree ["G-group1" "S-token-set-2"])
            token-set'  (get-in sets-tree' ["G-group1" "S-updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 5))
        (t/is (= (count group1') 3))
        (t/is (= (d/index-of (keys group1') "S-updated-name") 0))
        (t/is (= (:name token-set') "group1/updated-name"))
        (t/is (= (:description token-set') nil))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))
        sets-tree'))


    (t/testing "move-set-of-group"
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
            group1'     (get sets-tree' "G-group1")
            group2'     (get sets-tree' "G-group2")
            token-set   (get-in sets-tree ["G-group1" "S-token-set-2"])
            token-set'  (get-in sets-tree' ["G-group2" "S-updated-name"])]

        (t/is (= (ctob/set-count tokens-lib') 4))
        (t/is (= (count group1') 2))
        (t/is (= (count group2') 1))
        (t/is (nil? (get group1' "S-updated-name")))
        (t/is (= (:name token-set') "group2/updated-name"))
        (t/is (= (:description token-set') nil))
        (t/is (dt/is-after? (:modified-at token-set') (:modified-at token-set)))))

    (t/testing "delete-set-in-group"
      (let [tokens-lib  (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                            (ctob/add-set (ctob/make-token-set :name "group1/token-set-2")))

            tokens-lib' (-> tokens-lib
                            (ctob/delete-set-path "G-group1/S-token-set-2"))

            sets-tree'  (ctob/get-set-tree tokens-lib')
            token-set'  (get-in sets-tree' ["group1" "token-set-2"])]

        (t/is (= (ctob/set-count tokens-lib') 1))
        (t/is (= (count sets-tree') 1))
        (t/is (nil? token-set')))))

  (t/testing "grouped themes"
    (t/testing "grouped-themes"
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

    (t/testing "update-theme-in-groups"
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

    (t/testing "get-theme-groups"
      (let [token-lib (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :group "" :name "token-theme-1"))
                          (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-2"))
                          (ctob/add-theme (ctob/make-token-theme :group "group1" :name "token-theme-3"))
                          (ctob/add-theme (ctob/make-token-theme :group "group2" :name "token-theme-4")))
            token-groups (ctob/get-theme-groups token-lib)]
        (t/is (= token-groups ["group1" "group2"]))))

    (t/testing "rename-theme-in-groups"
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

    (t/testing "move-theme-of-group"
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

    (t/testing "delete-theme-in-group"
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

#?(:clj
   (t/deftest dtcg-encoding-decoding
     (t/testing "decode-dtcg-json"
       (let [json (-> (slurp "test/common_tests/types/data/tokens-multi-set-example.json")
                      (tr/decode-str))
             lib (ctob/decode-dtcg-json (ctob/ensure-tokens-lib nil) json)
             get-set-token (fn [set-name token-name]
                             (some-> (ctob/get-set lib set-name)
                                     (ctob/get-token token-name)
                                     (dissoc :modified-at)))
             token-theme (ctob/get-theme lib "group-1" "theme-1")]
         (t/is (= '("core" "light" "dark" "theme") (ctob/get-ordered-set-names lib)))
         (t/testing "set exists in theme"
           (t/is (= (:group token-theme) "group-1"))
           (t/is (= (:name token-theme) "theme-1"))
           (t/is (= (:sets token-theme) #{"light"})))
         (t/testing "tokens exist in core set"
           (t/is (= (get-set-token "core" "colors.red.600")
                    {:name "colors.red.600"
                     :type :color
                     :value "#e53e3e"
                     :description nil}))
           (t/is (= (get-set-token "core" "spacing.multi-value")
                    {:name "spacing.multi-value"
                     :type :spacing
                     :value "{dimension.sm} {dimension.xl}"
                     :description "You can have multiple values in a single spacing token"}))
           (t/is (= (get-set-token "theme" "button.primary.background")
                    {:name "button.primary.background"
                     :type :color
                     :value "{accent.default}"
                     :description nil})))
         (t/testing "invalid tokens got discarded"
           (t/is (nil? (get-set-token "typography" "H1.Bold"))))))

     (t/testing "encode-dtcg-json"
       (let [now (dt/now)
             tokens-lib (-> (ctob/make-tokens-lib)
                            (ctob/add-set (ctob/make-token-set :name "core"
                                                               :tokens {"colors.red.600"
                                                                        (ctob/make-token
                                                                         {:name "colors.red.600"
                                                                          :type :color
                                                                          :value "#e53e3e"})
                                                                        "spacing.multi-value"
                                                                        (ctob/make-token
                                                                         {:name "spacing.multi-value"
                                                                          :type :spacing
                                                                          :value "{dimension.sm} {dimension.xl}"
                                                                          :description "You can have multiple values in a single spacing token"})
                                                                        "button.primary.background"
                                                                        (ctob/make-token
                                                                         {:name "button.primary.background"
                                                                          :type :color
                                                                          :value "{accent.default}"})}))
                            (ctob/add-theme (ctob/make-token-theme :name "theme-1"
                                                                   :group "group-1"
                                                                   :modified-at now
                                                                   :sets #{"core"})))
             expected (ctob/encode-dtcg tokens-lib)]
         (t/is (= {"$themes" [{"description" nil
                               "group" "group-1"
                               "is-source" false
                               "modified-at" now
                               "name" "theme-1"
                               "selectedTokenSets" {"core" "enabled"}}]
                   "$metadata" {"tokenSetOrder" ["core"]}
                   "core"
                   {"colors" {"red" {"600" {"$value" "#e53e3e"
                                            "$type" "color"}}}
                    "spacing"
                    {"multi-value"
                     {"$value" "{dimension.sm} {dimension.xl}"
                      "$type" "spacing"
                      "$description" "You can have multiple values in a single spacing token"}}
                    "button"
                    {"primary" {"background" {"$value" "{accent.default}"
                                              "$type" "color"}}}}}
                  expected))))

     (t/testing "encode-decode-dtcg-json"
       (with-redefs [dt/now (constantly #inst "2024-10-16T12:01:20.257840055-00:00")]
         (let [tokens-lib (-> (ctob/make-tokens-lib)
                              (ctob/add-set (ctob/make-token-set :name "core"
                                                                 :tokens {"colors.red.600"
                                                                          (ctob/make-token
                                                                           {:name "colors.red.600"
                                                                            :type :color
                                                                            :value "#e53e3e"})
                                                                          "spacing.multi-value"
                                                                          (ctob/make-token
                                                                           {:name "spacing.multi-value"
                                                                            :type :spacing
                                                                            :value "{dimension.sm} {dimension.xl}"
                                                                            :description "You can have multiple values in a single spacing token"})
                                                                          "button.primary.background"
                                                                          (ctob/make-token
                                                                           {:name "button.primary.background"
                                                                            :type :color
                                                                            :value "{accent.default}"})})))
               encoded (ctob/encode-dtcg tokens-lib)
               with-prev-tokens-lib (ctob/decode-dtcg-json tokens-lib encoded)
               with-empty-tokens-lib (ctob/decode-dtcg-json (ctob/ensure-tokens-lib nil) encoded)]
           (t/testing "library got updated but data is equal"
             (t/is (not= with-prev-tokens-lib tokens-lib))
             (t/is (= @with-prev-tokens-lib @tokens-lib)))
           (t/testing "fresh tokens library is also equal"
             (= @with-empty-tokens-lib @tokens-lib)))))))
