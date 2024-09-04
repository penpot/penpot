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
                                              :description "test description"
                                              :is-source true
                                              :modified-at now
                                              :sets #{})]

      (t/is (= (:name token-theme1) "test-token-theme-1"))
      (t/is (nil? (:description token-theme1)))
      (t/is (false? (:is-source token-theme1)))
      (t/is (some? (:modified-at token-theme1)))
      (t/is (empty? (:sets token-theme1)))

      (t/is (= (:name token-theme2) "test-token-theme-2"))
      (t/is (= (:description token-theme2) "test description"))
      (t/is (true? (:is-source token-theme2)))
      (t/is (= (:modified-at token-theme2) now))
      (t/is (empty? (:sets token-theme2)))))

  (t/deftest invalid-token-theme
    (let [args {:name 777
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
          token-theme'  (ctob/get-theme tokens-lib' "test-token-theme")]

      (prn "lib" tokens-lib')
      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (first token-themes') token-theme))
      (t/is (= token-theme' token-theme))))

  (t/deftest update-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-theme "test-token-theme"
                                           (fn [token-theme]
                                             (assoc token-theme
                                                    :description "some description")))
                          (ctob/update-theme "not-existing-theme"
                                           (fn [token-theme]
                                             (assoc token-theme
                                                    :description "no-effect"))))

          token-theme   (ctob/get-theme tokens-lib "test-token-theme")
          token-theme'  (ctob/get-theme tokens-lib' "test-token-theme")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (:name token-theme') "test-token-theme"))
      (t/is (= (:description token-theme') "some description"))
      (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

  (t/deftest rename-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/update-theme "test-token-theme"
                                             (fn [token-theme]
                                               (assoc token-theme
                                                      :name "updated-name"))))

          token-theme   (ctob/get-theme tokens-lib "test-token-theme")
          token-theme'  (ctob/get-theme tokens-lib' "updated-name")]

      (t/is (= (ctob/theme-count tokens-lib') 1))
      (t/is (= (:name token-theme') "updated-name"))
      (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme)))))

  (t/deftest delete-token-theme
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))

          tokens-lib' (-> tokens-lib
                          (ctob/delete-theme "test-token-theme")
                          (ctob/delete-theme "not-existing-theme"))

          token-theme'  (ctob/get-theme tokens-lib' "updated-name")]

      (t/is (= (ctob/theme-count tokens-lib') 0))
      (t/is (nil? token-theme'))))

  (t/deftest toggle-set-in-theme
      (let [tokens-lib   (-> (ctob/make-tokens-lib)
                             (ctob/add-set (ctob/make-token-set :name "token-set-1"))
                             (ctob/add-set (ctob/make-token-set :name "token-set-2"))
                             (ctob/add-set (ctob/make-token-set :name "token-set-3"))
                             (ctob/add-theme (ctob/make-token-theme :name "test-token-theme")))
            tokens-lib'  (-> tokens-lib
                             (ctob/toggle-set-in-theme "test-token-theme" "token-set-1")
                             (ctob/toggle-set-in-theme "test-token-theme" "token-set-2")
                             (ctob/toggle-set-in-theme "test-token-theme" "token-set-2"))

            token-theme  (ctob/get-theme tokens-lib "test-token-theme")
            token-theme' (ctob/get-theme tokens-lib' "test-token-theme")]

        (t/is (dt/is-after? (:modified-at token-theme') (:modified-at token-theme))))))


(t/testing "serialization"
  (t/deftest transit-serialization
    (let [tokens-lib  (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :name "test-token-set"))
                          (ctob/add-token-in-set "test-token-set" (ctob/make-token :name "test-token"
                                                                                   :type :boolean
                                                                                   :value true))
                          (ctob/add-theme (ctob/make-token-theme :name "test-token-theme"))
                          (ctob/toggle-set-in-theme "test-token-theme" "test-token-set"))
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
                           (ctob/toggle-set-in-theme "test-token-theme" "test-token-set"))
          encoded-blob (fres/encode tokens-lib)
          tokens-lib'  (fres/decode encoded-blob)]

      (t/is (ctob/valid-tokens-lib? tokens-lib'))
      (t/is (= (ctob/set-count tokens-lib') 1))
      (t/is (= (ctob/theme-count tokens-lib') 1)))))
