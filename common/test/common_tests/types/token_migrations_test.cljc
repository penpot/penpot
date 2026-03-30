;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.token-migrations-test
  #?(:clj
     (:require
      [app.common.data :as d]
      [app.common.test-helpers.ids-map :as thi]
      [app.common.time :as ct]
      [app.common.types.tokens-lib :as ctob]
      [clojure.datafy :refer [datafy]]
      [clojure.test :as t])))

#?(:clj
   (t/deftest test-v1-5-fix-token-names
     ;; Use a less precission clock, so `modified-at` dates keep being equal
     ;; after serializing from fressian.
     (alter-var-root #'ct/*clock* (constantly (ct/tick-millis-clock)))

     (t/testing "empty tokens-lib should not need any action"
       (let [tokens-lib  (ctob/make-tokens-lib)
             tokens-lib' (-> tokens-lib
                             (datafy)
                             (#'app.common.types.tokens-lib/migrate-to-v1-5)
                             (ctob/make-tokens-lib))]
         (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

     (t/testing "tokens with valid names should not need any action"
       (let [tokens-lib  (-> (ctob/make-tokens-lib)
                             (ctob/add-set (ctob/make-token-set :id (thi/new-id! :test-token-set)
                                                                :name "test-token-set"))
                             (ctob/add-token (thi/id :test-token-set)
                                             (ctob/make-token :name "test-token-1"
                                                              :type :boolean
                                                              :value true))
                             (ctob/add-token (thi/id :test-token-set)
                                             (ctob/make-token :name "Test.Token_2"
                                                              :type :boolean
                                                              :value true))
                             (ctob/add-token (thi/id :test-token-set)
                                             (ctob/make-token :name "test.$token.3"
                                                              :type :boolean
                                                              :value true)))
             tokens-lib' (-> tokens-lib
                             (datafy)
                             (#'app.common.types.tokens-lib/migrate-to-v1-5)
                             (ctob/make-tokens-lib))]
         (t/is (empty? (d/map-diff (datafy tokens-lib) (datafy tokens-lib'))))))

     (t/testing "tokens with invalid names should be repaired"
       (let [;; Need to use low level constructors to avoid schema checks
             bad-token  (ctob/map->Token {:id (thi/new-id! :bad-token)
                                          :name "$.test-token with / and spacesAndSymbols%&."
                                          :type :boolean
                                          :value true
                                          :description ""
                                          :modified-at (ct/now)})
             token-set  (ctob/map->token-set {:id (thi/new-id! :token-set)
                                              :name "test-token-set"
                                              :description ""
                                              :modified-at (ct/now)
                                              :tokens (d/ordered-map (ctob/get-name bad-token) bad-token)})
             token-theme (ctob/make-hidden-theme {:modified-at (ct/now)})
             tokens-lib (ctob/map->tokens-lib {:sets (d/ordered-map (str "S-" (ctob/get-name token-set)) token-set)
                                               :themes (d/ordered-map
                                                        (:group token-theme)
                                                        (d/ordered-map
                                                         (:name token-theme)
                                                         token-theme))
                                               :active-themes #{(ctob/get-name token-theme)}})

             tokens-lib' (-> tokens-lib
                             (datafy)
                             (#'app.common.types.tokens-lib/migrate-to-v1-5)
                             (ctob/make-tokens-lib))

             expected-name "test-tokenwith.andspacesAndSymbols??"

             token-sets' (ctob/get-set-tree tokens-lib')
             token-set'  (ctob/get-set-by-name tokens-lib' "test-token-set")
             tokens'     (ctob/get-tokens tokens-lib' (ctob/get-id token-set'))
             bad-token'  (ctob/get-token-by-name tokens-lib' "test-token-set" expected-name)]

         (t/is (= (count token-sets') 1))
         (t/is (= (count tokens') 1))
         (t/is (= (ctob/get-id token-set') (ctob/get-id token-set)))
         (t/is (= (ctob/get-name token-set') (ctob/get-name token-set)))
         (t/is (= (ctob/get-description token-set') (ctob/get-description token-set)))
         (t/is (= (ctob/get-modified-at token-set') (ctob/get-modified-at token-set)))
         (t/is (= (ctob/get-id bad-token') (ctob/get-id bad-token)))
         (t/is (= (ctob/get-name bad-token') expected-name))
         (t/is (= (ctob/get-description bad-token') (ctob/get-description bad-token)))
         (t/is (= (ctob/get-modified-at bad-token') (ctob/get-modified-at bad-token)))
         (t/is (= (:type bad-token') (:type bad-token)))
         (t/is (= (:value bad-token') (:value bad-token)))))))