;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.logic.token-data-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn setup-file []
  (cthf/sample-file :file-1 :page-label :page-1))

(defn setup-file-with-token-lib
  []
  (-> (setup-file)
      (assoc-in [:data :tokens-lib]
                (-> (ctob/make-tokens-lib)
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :test-token-set)
                                                       :name "Set A"))))))

(t/deftest add-set
  (t/async
    done
    (let [file   (setup-file-with-token-lib)
          store  (ths/setup-store file)
          events [(dwtl/create-token-set "Set B")]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'       (ths/get-file-from-state new-state)
               tokens-lib' (toht/get-tokens-lib file')
               sets'       (ctob/get-sets tokens-lib')
               set-b'      (ctob/get-set-by-name tokens-lib' "Set B")]

           (t/testing "Token lib contains two sets"
             (t/is (= (count sets') 2))
             (t/is (some? set-b')))))))))

(t/deftest rename-set
  (t/async
    done
    (let [file       (setup-file-with-token-lib)
          store      (ths/setup-store file)
          tokens-lib (toht/get-tokens-lib file)
          set-a      (ctob/get-set-by-name tokens-lib "Set A")
          events     [(dwtl/update-token-set (ctob/rename set-a "Set A updated")
                                             "Set A updated")]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'       (ths/get-file-from-state new-state)
               tokens-lib' (toht/get-tokens-lib file')
               sets'       (ctob/get-sets tokens-lib')
               set-a'      (ctob/get-set-by-name tokens-lib' "Set A updated")]

           (t/testing "Set has been renamed"
             (t/is (= (count sets') 1))
             (t/is (some? set-a')))))))))

(t/deftest duplicate-set
  (t/async
    done
    (let [file   (setup-file-with-token-lib)
          store  (ths/setup-store file)
          events [(dwtl/duplicate-token-set (cthi/id :test-token-set))]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'     (ths/get-file-from-state new-state)
               token-lib (toht/get-tokens-lib file')
               sets      (ctob/get-sets token-lib)]

           (t/testing "Token lib contains two sets"
             (t/is (= (count sets) 2)))))))))

(t/deftest duplicate-non-exist-set
  (t/async
    done
    (let [file   (setup-file-with-token-lib)
          store  (ths/setup-store file)
          events [(dwtl/duplicate-token-set (uuid/next))]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'     (ths/get-file-from-state new-state)
               token-lib (toht/get-tokens-lib file')
               sets      (ctob/get-sets token-lib)]

           (t/testing "Token lib contains one set"
             (t/is (= (count sets) 1))))))))

  (t/deftest delete-set
    (t/async
      done
      (let [file       (setup-file-with-token-lib)
            store      (ths/setup-store file)
            events     [(dwtl/delete-token-set (cthi/id :test-token-set))]]

        (tohs/run-store-async
         store done events
         (fn [new-state]
           (let [file'       (ths/get-file-from-state new-state)
                 tokens-lib' (toht/get-tokens-lib file')
                 sets'       (ctob/get-sets tokens-lib')]

             (t/testing "Set has been deleted"
               (t/is (= (count sets') 0))))))))))