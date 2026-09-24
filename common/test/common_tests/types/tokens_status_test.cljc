;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns common-tests.types.tokens-status-test
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.transit :as tr]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [clojure.datafy :refer [datafy]]
   [clojure.test :as t]))

(t/deftest make-tokens-status
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                          :active-set-ids #{set-id})]
    (t/is (ctos/tokens-status? status))
    (t/is (ctos/check-tokens-status status))
    (t/is (= 1 (count (ctos/get-active-theme-ids status))))
    (t/is (ctos/theme-active? status theme-id))
    (t/is (= 1 (count (ctos/get-active-set-ids status))))
    (t/is (ctos/set-active? status set-id))))

(t/deftest make-tokens-status-defaults
  (let [status (ctos/make-tokens-status)]
    (t/is (ctos/tokens-status? status))
    (t/is (ctos/check-tokens-status status))
    (t/is (= 0 (count (ctos/get-active-theme-ids status))))
    (t/is (= 0 (count (ctos/get-active-set-ids status))))))

(t/deftest make-invalid-tokens-status
  (t/testing "non-set for active-themes"
    (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception)
                            #"expected valid params for tokens-status"
                            (ctos/make-tokens-status :active-theme-ids []))))
  (t/testing "non-uuid in active-sets"
    (t/is (thrown-with-msg? #?(:cljs js/Error :clj Exception)
                            #"expected valid params for tokens-status"
                            (ctos/make-tokens-status :active-set-ids #{"not-a-uuid"})))))

(t/deftest set-tokens-status
  (let [theme1-id (uuid/next)
        theme2-id (uuid/next)
        theme3-id (uuid/next)
        set1-id (uuid/next)
        set2-id (uuid/next)
        set3-id (uuid/next)
        status (-> (ctos/make-tokens-status {:active-theme-ids #{theme3-id}
                                             :active-set-ids #{set3-id}})
                   (ctos/set-tokens-status #{theme1-id theme2-id} #{set1-id set2-id}))]
    (t/is (= #{theme1-id theme2-id} (ctos/get-active-theme-ids status)))
    (t/is (= #{set1-id set2-id} (ctos/get-active-set-ids status)))))

(t/deftest datafy-tokens-status
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                          :active-set-ids #{set-id})
        result   (datafy status)]
    (t/is (map? result))
    (t/is (not (ctos/tokens-status? result)))
    (t/is (= (:active-theme-ids result) #{theme-id}))
    (t/is (= (:active-set-ids result) #{set-id}))))

(t/deftest transit-serialization
  (let [theme-id (uuid/next)
        set-id   (uuid/next)
        status   (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                          :active-set-ids #{set-id})
        encoded  (tr/encode-str status)
        status'  (tr/decode-str encoded)]
    (t/is (ctos/tokens-status? status'))
    (t/is (= (datafy status') (datafy status)))))

#?(:clj
   (t/deftest fressian-serialization
     (let [theme-id  (uuid/next)
           set-id    (uuid/next)
           status    (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                              :active-set-ids #{set-id})
           encoded   (fres/encode status)
           status'   (fres/decode encoded)]
       (t/is (ctos/tokens-status? status'))
       (t/is (= (datafy status') (datafy status))))))

#?(:clj
   (t/deftest json-serialization
     (let [theme-id (uuid/next)
           set-id   (uuid/next)
           status   (ctos/make-tokens-status :active-theme-ids #{theme-id}
                                             :active-set-ids #{set-id})
           json-str (json/write-str status)
           parsed   (json/read-str json-str :key-fn keyword)]
       (t/is (map? parsed))
       (t/is (= [(str theme-id)] (:active-theme-ids parsed)))
       (t/is (= [(str set-id)] (:active-set-ids parsed))))))
