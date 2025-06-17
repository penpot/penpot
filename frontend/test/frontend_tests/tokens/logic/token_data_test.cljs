;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.logic.token-data-test
  (:require
   [app.common.test-helpers.files :as cthf]
   [app.common.types.tokens-lib :as ctob]
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
                    (ctob/add-set (ctob/make-token-set :name "Set A"))))))

(t/deftest duplicate-set
  (t/async
    done
    (let [file   (setup-file-with-token-lib)
          store  (ths/setup-store file)
          events [(dwtl/duplicate-token-set "Set A" false)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'     (ths/get-file-from-state new-state)
               token-lib (toht/get-tokens-lib file')
               sets      (ctob/get-sets token-lib)
               set       (ctob/get-set token-lib "Set A")]

           (t/testing "Token lib contains two sets"
             (t/is (= (count sets) 2))
             (t/is (some? set)))))))))

(t/deftest duplicate-non-exist-set
  (t/async
    done
    (let [file   (setup-file-with-token-lib)
          store  (ths/setup-store file)
          events [(dwtl/duplicate-token-set "Set B" false)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'     (ths/get-file-from-state new-state)
               token-lib (toht/get-tokens-lib file')
               sets      (ctob/get-sets token-lib)
               set       (ctob/get-set token-lib "Set B")]

           (t/testing "Token lib contains one set"
             (t/is (= (count sets) 1))
             (t/is (nil? set)))))))))