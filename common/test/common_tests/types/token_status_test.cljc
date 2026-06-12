;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.token-status-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.test-helpers.ids-map :as thi]
   [app.common.transit :as tr]
   [app.common.types.token-status :as ctos]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/deftest make-token-status
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-token-status :active-themes #{theme-id}
                                         :active-sets #{set-id})]
    (t/is (ctos/token-status? status))
    (t/is (ctos/check-token-status status))
    (t/is (= (ctos/active-themes-count status) 1))
    (t/is (ctos/theme-active? status theme-id))
    (t/is (= (ctos/active-set-count status) 1))
    (t/is (ctos/set-active? status set-id))))

(t/deftest make-token-status-defaults
  (let [status (ctos/make-token-status)]
    (t/is (ctos/token-status? status))
    (t/is (ctos/check-token-status status))
    (t/is (= (ctos/active-themes-count status) 0))
    (t/is (= (ctos/active-set-count status) 0))))

(t/deftest make-invalid-token-status
  (t/testing "non-set for active-themes"
    (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception)
                            #"expected valid params for token-status"
                            (ctos/make-token-status :active-themes []))))
  (t/testing "non-uuid in active-sets"
    (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception)
                            #"expected valid params for token-status"
                            (ctos/make-token-status :active-sets #{"not-a-uuid"})))))

(t/deftest activate-theme
  (let [theme-id (uuid/next)
        tokens-lib (-> (ctob/make-tokens-lib)
                       (ctob/add-theme (ctob/make-token-theme :id theme-id :name "theme")))
        status   (ctos/make-token-status)
        status'  (ctos/activate-theme status tokens-lib theme-id)]
    (t/is (not (ctos/theme-active? status theme-id)))
    (t/is (ctos/theme-active? status' theme-id))
    (t/is (= (ctos/active-themes-count status') 1))))

(t/deftest deactivate-theme
  (let [theme-id (uuid/next)
        tokens-lib (-> (ctob/make-tokens-lib)
                       (ctob/add-theme (ctob/make-token-theme :id theme-id :name "theme")))
        status   (ctos/make-token-status :active-themes #{theme-id})
        status'  (ctos/deactivate-theme status tokens-lib theme-id)]
    (t/is (ctos/theme-active? status theme-id))
    (t/is (not (ctos/theme-active? status' theme-id)))
    (t/is (= (ctos/active-themes-count status') 0))))

(t/deftest set-theme-status
  (let [theme-id (uuid/next)
        tokens-lib (-> (ctob/make-tokens-lib)
                       (ctob/add-theme (ctob/make-token-theme :id theme-id :name "theme")))
        status   (ctos/make-token-status)
        status'  (ctos/set-theme-status status tokens-lib theme-id true)
        status'' (ctos/set-theme-status status' tokens-lib theme-id false)]
    (t/is (ctos/theme-active? status' theme-id))
    (t/is (not (ctos/theme-active? status'' theme-id)))
    (t/is (= (ctos/active-themes-count status') 1))
    (t/is (= (ctos/active-themes-count status'') 0))))

(t/deftest activate-set
  (let [set-id  (uuid/next)
        status  (ctos/make-token-status)
        status' (ctos/activate-set status set-id)]
    (t/is (not (ctos/set-active? status set-id)))
    (t/is (ctos/set-active? status' set-id))
    (t/is (= (ctos/active-set-count status') 1))))

(t/deftest deactivate-set
  (let [set-id  (uuid/next)
        status  (ctos/make-token-status :active-sets #{set-id})
        status' (ctos/deactivate-set status set-id)]
    (t/is (ctos/set-active? status set-id))
    (t/is (not (ctos/set-active? status' set-id)))
    (t/is (= (ctos/active-set-count status') 0))))

(t/deftest toggle-set-active
  (let [set-id   (uuid/next)
        status   (ctos/make-token-status)
        status'  (ctos/toggle-set-active status set-id)
        status'' (ctos/toggle-set-active status' set-id)]
    (t/is (ctos/set-active? status' set-id))
    (t/is (not (ctos/set-active? status'' set-id)))
    (t/is (= (ctos/active-set-count status') 1))
    (t/is (= (ctos/active-set-count status'') 0))))

(t/deftest datafy-token-status
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-token-status :active-themes #{theme-id}
                                          :active-sets #{set-id})
        result   (datafy status)]
    (t/is (map? result))
    (t/is (not (ctos/token-status? result)))
    (t/is (= (:active-themes result) #{theme-id}))
    (t/is (= (:active-sets result) #{set-id}))))

(t/deftest transit-serialization
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-token-status :active-themes #{theme-id}
                                          :active-sets #{set-id})
        encoded  (tr/encode-str status)
        status'  (tr/decode-str encoded)]
    (t/is (ctos/token-status? status'))
    (t/is (= (datafy status') (datafy status)))))

#?(:clj
   (t/deftest fressian-serialization
     (let [theme-id  (uuid/next)
           set-id    (uuid/next)
           status    (ctos/make-token-status :active-themes #{theme-id}
                                              :active-sets #{set-id})
           encoded   (fres/encode status)
           status'   (fres/decode encoded)]
       (t/is (ctos/token-status? status'))
       (t/is (= (datafy status') (datafy status))))))

#?(:clj
   (t/deftest json-serialization
     (let [theme-id (uuid/next)
           set-id   (uuid/next)
           status   (ctos/make-token-status :active-themes #{theme-id}
                                             :active-sets #{set-id})
           json-str (json/write-str status)
           parsed   (json/read-str json-str :key-fn keyword)]
       (t/is (map? parsed))
       (t/is (= [(str theme-id)] (:active-themes parsed)))
       (t/is (= [(str set-id)] (:active-sets parsed))))))

;; Make TokenStatus from a TokensLib (to migrate from legacy files)
(t/deftest make-token-status-from-tokens-lib
  (let [tokens-lib    (-> (ctob/make-tokens-lib)
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-a)
                                                             :name "set-a"))
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-b)
                                                             :name "set-b"))
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-c)
                                                             :name "set-c"))
                          (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-d)
                                                             :name "set-d"))
                          (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-1)
                                                                 :name "theme-1"
                                                                 :sets #{"set-a" "set-b"}))
                          (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-2)
                                                                 :name "theme-2"
                                                                 :sets #{"set-b"}))
                          (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-3)
                                                                 :name "theme-3"
                                                                 :sets #{"set-c" "set-d"}))
                          (ctob/set-active-themes #{"/theme-1" "/theme-2"}))
        token-status (ctos/make-token-status-from-lib tokens-lib)]
    (t/is (ctos/token-status? token-status))
    (t/is (ctos/check-token-status token-status))
    (t/is (= (ctos/active-themes-count token-status) 2))
    (t/is (ctos/theme-active? token-status (thi/id :theme-1)))
    (t/is (ctos/theme-active? token-status (thi/id :theme-2)))
    (t/is (= (ctos/active-set-count token-status) 2))
    (t/is (ctos/set-active? token-status (thi/id :set-a)))
    (t/is (ctos/set-active? token-status (thi/id :set-b)))))
